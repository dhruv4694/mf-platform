package com.mfplatform.mfplatform.notification.dto;

import com.mfplatform.mfplatform.distributor.Distributor;
import com.mfplatform.mfplatform.investor.Investor;
import com.mfplatform.mfplatform.notification.event.NotificationEvent;

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

    // ─── Template model records ───────────────────────────────────────────────
    // These are passed to Thymeleaf templates as the model object.
    // Using records keeps them immutable and concise.

    public record InvestorWelcomeModel(String name, String panNumber) {}

    public record DistributorPendingModel(String name, String arnCode) {}

    public record DistributorActivatedModel(String name, String arnCode) {}
}
