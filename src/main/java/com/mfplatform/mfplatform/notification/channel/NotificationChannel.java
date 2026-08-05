package com.mfplatform.mfplatform.notification.channel;

import com.mfplatform.mfplatform.notification.dto.NotificationPayload;

/**
 * NotificationChannel is the Strategy interface for notification delivery.
 *
 * STRATEGY PATTERN (third application in this project):
 * Each channel implements this interface. NotificationService holds a
 * List<NotificationChannel> injected by Spring — every @Component
 * that implements this interface is automatically included.
 *
 * Adding a new channel (e.g. WhatsApp, push notification):
 *   1. Create a new @Component implementing NotificationChannel
 *   2. Add notification.whatsapp.enabled: false to application.yml
 *   Zero changes to NotificationService or any existing channel.
 *
 * CURRENT IMPLEMENTATIONS:
 *   EmailNotificationChannel — sends HTML email via JavaMailSender + Thymeleaf
 *   SmsNotificationChannel   — stub implementation (logs the SMS body)
 *
 * isEnabled() PATTERN:
 * Each channel decides whether it's active. This is cleaner than a central
 * "which channels are enabled" configuration because:
 *   - Each channel knows its own configuration (SMTP host, SMS API key, etc.)
 *   - A channel can be disabled if its credentials aren't configured
 *   - NotificationService never needs to change when channels are added/removed
 */
public interface NotificationChannel {

    /**
     * Sends a notification through this channel.
     * Implementations must handle their own exceptions — a channel failure
     * must not prevent other channels from sending.
     * NotificationService wraps each send() in a try-catch for this reason.
     *
     * @param payload all data this channel might need
     */
    void send(NotificationPayload payload);

    /**
     * Whether this channel is currently active.
     * If false, NotificationService will skip this channel entirely.
     * Used to disable channels that aren't configured (no SMTP host,
     * no SMS API key, etc.) without removing them from the codebase.
     */
    boolean isEnabled();

    /**
     * Human-readable name for logging and audit trail.
     * e.g. "EMAIL", "SMS", "WHATSAPP"
     */
    String channelName();
}
