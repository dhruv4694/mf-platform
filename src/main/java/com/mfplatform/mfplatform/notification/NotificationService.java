package com.mfplatform.mfplatform.notification;

import com.mfplatform.mfplatform.notification.channel.NotificationChannel;
import com.mfplatform.mfplatform.notification.dto.NotificationPayload;
import com.mfplatform.mfplatform.notification.event.ApplicationEvents.*;
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

    public NotificationService(List<NotificationChannel> channels,
                                NotificationRepository notificationRepository) {
        this.channels = channels;
        this.notificationRepository = notificationRepository;
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
