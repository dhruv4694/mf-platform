package com.mfplatform.mfplatform.notification;

import com.mfplatform.mfplatform.folio.Folio;
import com.mfplatform.mfplatform.folio.FolioRepository;
import com.mfplatform.mfplatform.investor.Investor;
import com.mfplatform.mfplatform.investor.InvestorRepository;
import com.mfplatform.mfplatform.notification.channel.NotificationChannel;
import com.mfplatform.mfplatform.notification.dto.NotificationPayload;
import com.mfplatform.mfplatform.notification.event.ApplicationEvents.*;
import com.mfplatform.mfplatform.scheme.Scheme;
import com.mfplatform.mfplatform.scheme.SchemeRepository;
import com.mfplatform.mfplatform.sip.SipMandate;
import com.mfplatform.mfplatform.sip.SipMandateRepository;
import com.mfplatform.mfplatform.transaction.MfTransaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.List;

/**
 * NotificationService is the central hub for all outbound notifications.
 *
 * DESIGN:
 *   1. Listens to application events via @TransactionalEventListener(AFTER_COMMIT)
 *   2. Builds a NotificationPayload from the event data
 *   3. Dispatches through all enabled NotificationChannels
 *   4. Saves a Notification row for audit trail
 *
 * @Async:
 * Every listener is @Async — notification sending runs on the mf-async-*
 * thread pool, NOT the request thread. This means:
 *   - POST /auth/signup/investor returns immediately after the DB commit
 *   - The welcome email is sent in the background
 *   - A slow/failing email provider doesn't affect the user's response time
 *
 * @TransactionalEventListener(AFTER_COMMIT):
 * Critical distinction from @EventListener:
 *   - @EventListener fires DURING the transaction (investor row might not be visible)
 *   - @TransactionalEventListener(AFTER_COMMIT) fires AFTER the transaction commits
 *   - By the time our listener fires, the investor/distributor row is durably
 *     written and visible to any new transaction (including the email builder)
 *
 * CHANNEL LIST INJECTION:
 * Spring automatically injects ALL beans implementing NotificationChannel.
 * Adding a new channel means adding a @Component — zero changes here.
 *
 * ONE FAILURE DOESN'T BLOCK OTHERS:
 * Each channel send() is wrapped in try-catch. If email fails, SMS still sends.
 * If both fail, the Notification row is still saved (with a failure message).
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    /**
     * All notification channels — Spring injects every @Component
     * implementing NotificationChannel automatically.
     * Current beans: EmailNotificationChannel, SmsNotificationChannel.
     */
    private final List<NotificationChannel> channels;
    private final NotificationRepository    notificationRepository;
    private final FolioRepository           folioRepository;
    private final InvestorRepository        investorRepository;
    private final SchemeRepository          schemeRepository;
    private final SipMandateRepository      sipMandateRepository;

    public NotificationService(List<NotificationChannel> channels,
                                NotificationRepository notificationRepository,
                                FolioRepository folioRepository,
                                InvestorRepository investorRepository,
                                SchemeRepository schemeRepository,
                                SipMandateRepository sipMandateRepository) {
        this.channels = channels;
        this.notificationRepository = notificationRepository;
        this.folioRepository = folioRepository;
        this.investorRepository = investorRepository;
        this.schemeRepository = schemeRepository;
        this.sipMandateRepository = sipMandateRepository;
        log.info("NotificationService initialized with {} channel(s): {}",
                channels.size(),
                channels.stream().map(NotificationChannel::channelName).toList());
    }

    // ─── Event listeners ──────────────────────────────────────────────────────

    /**
     * Sends a welcome email+SMS to a newly registered investor.
     *
     * Triggered by: AuthService.signupInvestor() → publishes InvestorSignedUpEvent
     * Fires: AFTER the investor + user_account rows are committed
     * Thread: mf-async-* (not the HTTP request thread)
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onInvestorSignedUp(InvestorSignedUpEvent event) {
        log.info("Processing INVESTOR_WELCOME notification for investor {}",
                event.getInvestor().getId());

        NotificationPayload payload =
                NotificationPayload.investorWelcome(event.getInvestor());
        dispatch(payload);
    }

    /**
     * Sends a "your ARN verification is in progress" notification
     * when a distributor self-signs up.
     *
     * Triggered by: AuthService.signupDistributor()
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDistributorSignedUp(DistributorSignedUpEvent event) {
        log.info("Processing DISTRIBUTOR_SIGNUP_PENDING notification for distributor {}",
                event.getDistributor().getId());

        NotificationPayload payload =
                NotificationPayload.distributorSignupPending(event.getDistributor());
        dispatch(payload);
    }

    /**
     * Sends a "your account is now active" notification when the
     * background verification worker activates a distributor.
     *
     * Triggered by: DistributorVerificationWorker.activateAfterVerification()
     *
     * Note: DistributorVerificationWorker uses @Transactional, so this
     * event also correctly fires AFTER_COMMIT — after the status=ACTIVE
     * update is durably written.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDistributorActivated(DistributorActivatedEvent event) {
        log.info("Processing DISTRIBUTOR_ACTIVATED notification for distributor {}",
                event.getDistributor().getId());

        NotificationPayload payload =
                NotificationPayload.distributorActivated(event.getDistributor());
        dispatch(payload);
    }

    /**
     * Sends a "your request has been received" notification when a PENDING
     * purchase/redemption transaction is created (manual or SIP-originated).
     *
     * Triggered by: PurchaseService.createPurchase() / RedemptionService.createRedemption()
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTransactionCreated(TransactionCreatedEvent event) {
        MfTransaction transaction = event.getTransaction();
        log.info("Processing TRANSACTION_CREATED notification for transaction {}", transaction.getId());

        TransactionContext ctx = resolveTransactionContext(transaction);
        if (ctx == null) {
            return;
        }

        NotificationPayload payload = NotificationPayload.transactionCreated(
                transaction, ctx.investor(), ctx.folio(), ctx.scheme(), ctx.sipMandateReference());
        dispatch(payload);
    }

    /**
     * Sends a settlement-outcome notification (ALLOTTED or FAILED) when EOD
     * finishes processing a transaction.
     *
     * Triggered by: EodTransactionProcessor.processOne()
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTransactionSettled(TransactionSettledEvent event) {
        MfTransaction transaction = event.getTransaction();
        log.info("Processing TRANSACTION_SETTLED notification for transaction {}", transaction.getId());

        TransactionContext ctx = resolveTransactionContext(transaction);
        if (ctx == null) {
            return;
        }

        NotificationPayload payload = NotificationPayload.transactionSettled(
                transaction, ctx.investor(), ctx.folio(), ctx.scheme(),
                ctx.sipMandateReference(), event.getFailureReason());
        dispatch(payload);
    }

    /**
     * Sends a confirmation notification when a new SIP mandate is registered.
     *
     * Triggered by: SipMandateService.register()
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSipMandateCreated(SipMandateCreatedEvent event) {
        SipMandate mandate = event.getMandate();
        log.info("Processing SIP_MANDATE_CREATED notification for mandate {}", mandate.getId());

        Folio folio = folioRepository.findById(mandate.getFolioId()).orElse(null);
        if (folio == null) {
            log.warn("SIP_MANDATE_CREATED: folio {} not found for mandate {} — skipping notification",
                    mandate.getFolioId(), mandate.getId());
            return;
        }
        Investor investor = investorRepository.findById(folio.getInvestorId()).orElse(null);
        Scheme scheme = schemeRepository.findById(mandate.getSchemeId()).orElse(null);
        if (investor == null || scheme == null) {
            log.warn("SIP_MANDATE_CREATED: missing investor/scheme for mandate {} — skipping notification",
                    mandate.getId());
            return;
        }

        NotificationPayload payload = NotificationPayload.sipMandateCreated(
                mandate, investor, scheme, event.getScheduleDescription());
        dispatch(payload);
    }

    /**
     * Resolves the folio/investor/scheme/SIP-mandate-reference context shared
     * by both transaction-related listeners. Returns null (logging a warning)
     * if any required entity is missing — this can only happen if the folio
     * or scheme was deleted between transaction creation and notification
     * dispatch, which doesn't happen in this platform's actual flows, but the
     * listener still needs to fail safe rather than throw on the async thread.
     */
    private TransactionContext resolveTransactionContext(MfTransaction transaction) {
        Folio folio = folioRepository.findById(transaction.getFolioId()).orElse(null);
        if (folio == null) {
            log.warn("Folio {} not found for transaction {} — skipping notification",
                    transaction.getFolioId(), transaction.getId());
            return null;
        }
        Investor investor = investorRepository.findById(folio.getInvestorId()).orElse(null);
        Scheme scheme = schemeRepository.findById(transaction.getSchemeId()).orElse(null);
        if (investor == null || scheme == null) {
            log.warn("Missing investor/scheme for transaction {} — skipping notification",
                    transaction.getId());
            return null;
        }

        String sipMandateReference = transaction.getSipMandateId() != null
                ? sipMandateRepository.findById(transaction.getSipMandateId())
                        .map(SipMandate::getMandateReference)
                        .orElse(null)
                : null;

        return new TransactionContext(investor, folio, scheme, sipMandateReference);
    }

    private record TransactionContext(Investor investor, Folio folio, Scheme scheme, String sipMandateReference) {}

    // ─── Dispatch ─────────────────────────────────────────────────────────────

    /**
     * Dispatches a notification through all enabled channels.
     *
     * STREAMS: filter enabled channels, send through each, catch failures.
     * Each channel failure is caught individually — one failure doesn't
     * prevent other channels from sending.
     *
     * After dispatch, saves a Notification row for audit trail regardless
     * of whether all channels succeeded.
     */
    private void dispatch(NotificationPayload payload) {
        List<String> sentVia = channels.stream()
                .filter(NotificationChannel::isEnabled)
                .map(channel -> {
                    try {
                        channel.send(payload);
                        return channel.channelName();
                    } catch (Exception ex) {
                        log.error("Channel {} failed for event {}: {}",
                                channel.channelName(), payload.event(), ex.getMessage());
                        return null;
                    }
                })
                .filter(name -> name != null)
                .toList();

        // Save audit record — even if no channels sent (useful for debugging)
        String message = sentVia.isEmpty()
                ? "Notification attempted but no channels delivered for event: " + payload.event()
                : "Notification sent via " + String.join(", ", sentVia)
                  + " for event: " + payload.event()
                  + " to: " + payload.recipientEmail();

        notificationRepository.save(
                Notification.builder()
                        .investorId(payload.investorId())
                        .transactionId(payload.transactionId())
                        .message(message)
                        .sentAt(Instant.now())
                        .build()
        );

        log.info("Notification dispatched | event={} | channels={} | recipient={}",
                payload.event(), sentVia, payload.recipientEmail());
    }
}
