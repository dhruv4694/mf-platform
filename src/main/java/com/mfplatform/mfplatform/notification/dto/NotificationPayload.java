package com.mfplatform.mfplatform.notification.dto;

import com.mfplatform.mfplatform.distributor.Distributor;
import com.mfplatform.mfplatform.folio.Folio;
import com.mfplatform.mfplatform.investor.Investor;
import com.mfplatform.mfplatform.notification.event.NotificationEvent;
import com.mfplatform.mfplatform.scheme.Scheme;
import com.mfplatform.mfplatform.sip.SipMandate;
import com.mfplatform.mfplatform.transaction.MfTransaction;
import com.mfplatform.mfplatform.transaction.TransactionStatus;
import com.mfplatform.mfplatform.transaction.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * NotificationPayload is a single immutable value object carrying everything
 * any notification channel might need for a given event.
 *
 * WHY ONE PAYLOAD FOR ALL CHANNELS:
 * Each channel picks what it needs:
 *   - Email uses: recipientEmail, subject, templateName, templateVariables
 *   - SMS uses:   recipientPhone, smsBody
 *   - Both use:   recipientName, event
 *
 * The payload is built once (in NotificationService) and passed to every
 * enabled channel — channels don't talk to each other or the DB.
 *
 * NAMED FACTORY METHODS:
 * Instead of a massive constructor, each event type has a descriptive
 * static factory method. This makes call sites readable:
 *   NotificationPayload.investorWelcome(investor)     ← clear intent
 *   new NotificationPayload("Priya", "priya@...", ...) ← opaque
 *
 * Using a record makes the payload immutable — once built, no channel
 * can accidentally modify it for the next channel.
 */
public record NotificationPayload(

        // ─── Recipient ────────────────────────────────────────────────────────
        String recipientName,
        String recipientEmail,
        String recipientPhone,   // nullable — not all investors provide a phone

        // ─── Email-specific ───────────────────────────────────────────────────
        String subject,
        String templateName,     // Thymeleaf template: "email/welcome-investor"
        Object templateModel,    // data object passed to the Thymeleaf template

        // ─── SMS-specific ─────────────────────────────────────────────────────
        String smsBody,          // plain text, kept under 160 chars where possible

        // ─── Metadata ─────────────────────────────────────────────────────────
        NotificationEvent event, // used for logging and audit trail
        Long investorId,         // for saving to notification table (nullable)
        Long transactionId       // for saving to notification table (nullable)

) {

    // ─── Factory methods ──────────────────────────────────────────────────────

    /**
     * Welcome email/SMS for a newly registered investor.
     */
    public static NotificationPayload investorWelcome(Investor investor) {
        String name = investor.getName();
        return new NotificationPayload(
                name,
                investor.getEmail(),
                null, // phone added when investor model has phone field
                "Welcome to MF Platform — " + name,
                "email/welcome-investor",
                new InvestorWelcomeModel(name, investor.getPanNumber()),
                "Hi " + name + "! Your MF Platform account is ready. " +
                "Log in to start investing.",
                NotificationEvent.INVESTOR_WELCOME,
                investor.getId(),
                null
        );
    }

    /**
     * Notification to a distributor that their ARN is being verified.
     */
    public static NotificationPayload distributorSignupPending(Distributor distributor) {
        String name = distributor.getName();
        return new NotificationPayload(
                name,
                distributor.getEmail(),
                null,
                "ARN Verification in Progress — MF Platform",
                "email/distributor-pending",
                new DistributorPendingModel(name, distributor.getArnCode()),
                "Hi " + name + "! Your ARN " + distributor.getArnCode() +
                " is being verified. We'll notify you once it's complete.",
                NotificationEvent.DISTRIBUTOR_SIGNUP_PENDING,
                null,
                null
        );
    }

    /**
     * Notification to a distributor that their account is now active.
     */
    public static NotificationPayload distributorActivated(Distributor distributor) {
        String name = distributor.getName();
        return new NotificationPayload(
                name,
                distributor.getEmail(),
                null,
                "Your Distributor Account is Now Active — MF Platform",
                "email/distributor-activated",
                new DistributorActivatedModel(name, distributor.getArnCode()),
                "Great news, " + name + "! Your distributor account is verified " +
                "and active. You can now onboard investors.",
                NotificationEvent.DISTRIBUTOR_ACTIVATED,
                null,
                null
        );
    }

    /**
     * Notification that a PENDING purchase/redemption transaction was created
     * (manual or SIP-originated). Subject and body both distinguish SIP from
     * manual purchases — see the class javadoc on why this is one template
     * with a conditional rather than a separate SIP template.
     *
     * @param sipMandateReference non-null only when the transaction was
     *                            SIP-originated (transaction.getSipMandateId() != null)
     */
    public static NotificationPayload transactionCreated(
            MfTransaction transaction, Investor investor, Folio folio, Scheme scheme,
            String sipMandateReference) {
        String name = investor.getName();
        boolean sipOriginated = transaction.getSipMandateId() != null;
        boolean isPurchase = transaction.getType() == TransactionType.PURCHASE;

        String subject = isPurchase
                ? (sipOriginated ? "Your SIP installment has been initiated" : "Purchase request received")
                : "Redemption request received";

        return new NotificationPayload(
                name,
                investor.getEmail(),
                null,
                subject,
                "email/transaction-created",
                new TransactionCreatedModel(
                        name,
                        transaction.getType().name(),
                        folio.getFolioNumber(),
                        scheme.getSchemeName(),
                        transaction.getRequestAmount(),
                        transaction.getRequestUnits(),
                        transaction.getBusinessDate(),
                        sipOriginated,
                        sipMandateReference
                ),
                "Hi " + name + "! Your " + (isPurchase ? "purchase" : "redemption") +
                " request for " + scheme.getSchemeName() + " has been received and will be " +
                "processed at the next settlement cycle.",
                NotificationEvent.TRANSACTION_CREATED,
                investor.getId(),
                transaction.getId()
        );
    }

    /**
     * Notification that a transaction reached a terminal EOD settlement
     * outcome — ALLOTTED or FAILED. Subject is both SIP-aware and status-aware
     * (six distinct variants — see email-service-spec-1.md).
     *
     * @param sipMandateReference non-null only when SIP-originated
     * @param failureReason       raw AllotmentResult.Failed reason, non-null only when FAILED —
     *                            translated to investor-appropriate wording here, not upstream
     */
    public static NotificationPayload transactionSettled(
            MfTransaction transaction, Investor investor, Folio folio, Scheme scheme,
            String sipMandateReference, String failureReason) {
        String name = investor.getName();
        boolean sipOriginated = transaction.getSipMandateId() != null;
        boolean isPurchase = transaction.getType() == TransactionType.PURCHASE;
        boolean allotted = transaction.getStatus() == TransactionStatus.ALLOTTED;

        String subject;
        if (isPurchase) {
            if (allotted) {
                subject = sipOriginated ? "Your SIP installment has been processed" : "Your purchase has been completed";
            } else {
                subject = sipOriginated ? "Your SIP installment could not be processed" : "Your purchase could not be completed";
            }
        } else {
            subject = allotted ? "Your redemption has been completed" : "Your redemption could not be completed";
        }

        String plainFailureReason = allotted ? null : translateFailureReason(failureReason);

        String smsBody = allotted
                ? "Hi " + name + "! Your " + (isPurchase ? "purchase" : "redemption") +
                  " for " + scheme.getSchemeName() + " has been completed."
                : "Hi " + name + ", we were unable to process your " + (isPurchase ? "purchase" : "redemption") +
                  " for " + scheme.getSchemeName() + ". " + plainFailureReason;

        return new NotificationPayload(
                name,
                investor.getEmail(),
                null,
                subject,
                "email/transaction-settled",
                new TransactionSettledModel(
                        name,
                        transaction.getType().name(),
                        folio.getFolioNumber(),
                        scheme.getSchemeName(),
                        transaction.getStatus().name(),
                        transaction.getAllottedUnits(),
                        transaction.getApplicableNav() != null ? transaction.getApplicableNav().getNavValue() : null,
                        transaction.getBusinessDate(),
                        sipOriginated,
                        sipMandateReference,
                        plainFailureReason
                ),
                smsBody,
                NotificationEvent.TRANSACTION_SETTLED,
                investor.getId(),
                transaction.getId()
        );
    }

    /**
     * Notification that a new SIP mandate was registered.
     * scheduleDescription is passed in rather than recomputed — see
     * SipMandateService.buildScheduleDescription(), the single source of
     * truth for this formatting (also used by the SIP registration API
     * response, so the email always matches what the UI showed).
     */
    public static NotificationPayload sipMandateCreated(
            SipMandate mandate, Investor investor, Scheme scheme, String scheduleDescription) {
        String name = investor.getName();
        return new NotificationPayload(
                name,
                investor.getEmail(),
                null,
                "Your SIP mandate has been registered",
                "email/sip-mandate-created",
                new SipMandateCreatedModel(
                        name,
                        scheme.getSchemeName(),
                        mandate.getAmount(),
                        mandate.getFrequency().name(),
                        mandate.getMandateReference(),
                        scheduleDescription
                ),
                "Hi " + name + "! Your SIP mandate " + mandate.getMandateReference() +
                " for " + scheme.getSchemeName() + " has been registered. " + scheduleDescription + ".",
                NotificationEvent.SIP_MANDATE_CREATED,
                investor.getId(),
                null
        );
    }

    /**
     * Translates a raw AllotmentResult.Failed reason into investor-appropriate
     * plain language. Falls back to a generic message for reasons not
     * explicitly recognized, rather than surfacing raw internal wording.
     */
    private static String translateFailureReason(String rawReason) {
        if (rawReason == null) {
            return "An unexpected error occurred while processing your request.";
        }
        String lower = rawReason.toLowerCase();
        if (lower.contains("insufficient")) {
            return "There were insufficient units available to complete this request.";
        }
        if (lower.contains("holding update failed") || lower.contains("concurrent")) {
            return "The request could not be completed due to a temporary processing conflict. Please contact support if this persists.";
        }
        return "The request could not be completed. Please contact support for details.";
    }

    // ─── Template model records ───────────────────────────────────────────────
    // These are passed to Thymeleaf templates as the model object.
    // Using records keeps them immutable and concise.

    public record InvestorWelcomeModel(String name, String panNumber) {}

    public record DistributorPendingModel(String name, String arnCode) {}

    public record DistributorActivatedModel(String name, String arnCode) {}

    public record TransactionCreatedModel(
            String name,
            String transactionType,      // PURCHASE / REDEMPTION
            String folioNumber,
            String schemeName,
            BigDecimal amount,           // null for redemption-by-units
            BigDecimal units,            // null unless redemption-by-units
            LocalDate businessDate,
            boolean sipOriginated,
            String sipMandateReference   // null unless sipOriginated
    ) {}

    public record TransactionSettledModel(
            String name,
            String transactionType,
            String folioNumber,
            String schemeName,
            String status,               // ALLOTTED / FAILED
            BigDecimal allottedUnits,    // null unless ALLOTTED
            BigDecimal applicableNav,    // null unless ALLOTTED
            LocalDate businessDate,
            boolean sipOriginated,
            String sipMandateReference,  // null unless sipOriginated
            String failureReason         // null unless FAILED
    ) {}

    public record SipMandateCreatedModel(
            String name,
            String schemeName,
            BigDecimal amount,
            String frequency,
            String mandateReference,
            String scheduleDescription
    ) {}
}
