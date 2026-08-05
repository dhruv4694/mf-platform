package com.mfplatform.mfplatform.notification.channel;

import com.mfplatform.mfplatform.notification.dto.NotificationPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * SmsNotificationChannel is a stub implementation of the SMS channel.
 *
 * CURRENT STATE: STUB
 * This implementation logs the SMS body to the console instead of sending
 * a real SMS. This is intentional for a portfolio project:
 *   - Demonstrates the Strategy pattern is in place
 *   - Shows the channel abstraction works end-to-end
 *   - Doesn't require a paid SMS provider account to demo
 *   - Is disabled by default (notification.sms.enabled: false)
 *
 * UPGRADING TO A REAL PROVIDER:
 * Replace the log.info() in send() with one of:
 *
 *   Twilio:
 *     com.twilio.rest.api.v2010.account.Message.creator(
 *         new PhoneNumber(payload.recipientPhone()),
 *         new PhoneNumber(twilioFromNumber),
 *         payload.smsBody()
 *     ).create();
 *
 *   MSG91 (popular in India for OTP/transactional SMS):
 *     restTemplate.postForObject(msg91ApiUrl, buildMsg91Request(payload), String.class);
 *
 *   AWS SNS:
 *     snsClient.publish(PublishRequest.builder()
 *         .phoneNumber(payload.recipientPhone())
 *         .message(payload.smsBody())
 *         .build());
 *
 * Add the provider's SDK to pom.xml and configure credentials via
 * environment variables. No changes needed to NotificationService or
 * any other channel.
 *
 * OPTIONAL CHANNEL:
 * SMS is optional at two levels:
 *   1. System level: notification.sms.enabled: false (default)
 *      → channel is skipped entirely
 *   2. Investor level: payload.recipientPhone() == null
 *      → investor hasn't provided a phone number, channel skips gracefully
 */
@Component
public class SmsNotificationChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(SmsNotificationChannel.class);

    @Value("${notification.sms.enabled:false}")
    private boolean enabled;

    @Value("${notification.sms.provider:stub}")
    private String provider;

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public String channelName() {
        return "SMS";
    }

    /**
     * Sends an SMS (currently: logs it instead of sending).
     *
     * Checks:
     *   1. recipientPhone is not null (investor opted in to SMS)
     *   2. smsBody is not null (payload has SMS content)
     *   3. SMS body is within 160 chars (single SMS unit; longer = split/charged extra)
     */
    @Override
    public void send(NotificationPayload payload) {
        if (payload.recipientPhone() == null) {
            log.debug("Skipping SMS for event {} — no phone number on record",
                    payload.event());
            return;
        }

        if (payload.smsBody() == null) {
            log.warn("Skipping SMS for event {} — no SMS body in payload", payload.event());
            return;
        }

        String body = payload.smsBody();
        if (body.length() > 160) {
            log.warn("SMS body for event {} is {} chars (>160 — will be split into multiple messages)",
                    payload.event(), body.length());
        }

        // STUB: log instead of sending
        // Replace this block with real provider code when upgrading
        log.info("[SMS STUB | provider={}] To: {} | Body: {}",
                provider, payload.recipientPhone(), body);

        // In a real implementation, record the SMS provider's message ID
        // and store it alongside the Notification row for delivery tracking
    }
}
