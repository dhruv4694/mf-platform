package com.mfplatform.mfplatform.notification.event;

/**
 * NotificationEvent enumerates every business event that may trigger
 * one or more notifications (email, SMS, push, etc.).
 *
 * Each value maps to:
 *   - A specific email template in resources/templates/email/
 *   - A specific SMS message built in SmsNotificationChannel
 *   - A subject line in NotificationPayload
 *
 * Adding a new notification type:
 *   1. Add a value here
 *   2. Add a factory method on NotificationPayload
 *   3. Add an email template (optional)
 *   Zero changes to NotificationService or any channel implementation.
 */
public enum NotificationEvent {

    /** New investor completed self-signup */
    INVESTOR_WELCOME,

    /** Distributor completed self-signup, ARN verification in progress */
    DISTRIBUTOR_SIGNUP_PENDING,

    /** Distributor's ARN verified, account now ACTIVE */
    DISTRIBUTOR_ACTIVATED,

    /** Distributor's ARN verification failed, account REJECTED */
    DISTRIBUTOR_REJECTED,

    /** Purchase transaction fully allotted */
    PURCHASE_ALLOTTED,

    /** Redemption transaction fully processed */
    REDEMPTION_PROCESSED,

    /** SIP mandate successfully registered */
    SIP_REGISTERED,

    /** SIP installment payment bounced */
    SIP_INSTALLMENT_BOUNCED,

    /** SIP mandate completed (end date passed) */
    SIP_COMPLETED
}
