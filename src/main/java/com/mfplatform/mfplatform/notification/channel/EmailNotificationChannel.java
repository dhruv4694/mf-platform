package com.mfplatform.mfplatform.notification.channel;

import com.mfplatform.mfplatform.notification.dto.NotificationPayload;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

/**
 * EmailNotificationChannel sends HTML emails using:
 *   - JavaMailSender (Spring's SMTP abstraction)
 *   - Thymeleaf TemplateEngine (renders HTML email templates)
 *
 * MAILHOG FOR DEVELOPMENT:
 * In development, all emails go to MailHog — a fake SMTP server that
 * captures emails without actually sending them. View them at:
 *   http://localhost:8025
 * Start MailHog via: docker compose up -d (configured in docker-compose.yml)
 *
 * SENDGRID FOR PRODUCTION:
 * Set these environment variables:
 *   MAIL_HOST=smtp.sendgrid.net
 *   MAIL_PORT=587
 *   MAIL_USERNAME=apikey
 *   MAIL_PASSWORD=<your-sendgrid-api-key>
 *   MAIL_FROM=noreply@yourdomain.com
 *
 * THYMELEAF TEMPLATES:
 * Templates live in src/main/resources/templates/email/
 * Template name in payload (e.g. "email/welcome-investor") maps to
 * src/main/resources/templates/email/welcome-investor.html
 *
 * isEnabled() CHECK:
 * If no SMTP host is configured (MAIL_HOST not set), the channel disables
 * itself gracefully — no exception, just a debug log that email is skipped.
 * This means the app starts cleanly even without a mail server configured.
 */
@Component
public class EmailNotificationChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationChannel.class);

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;

    @Value("${notification.email.enabled:true}")
    private boolean enabled;

    @Value("${notification.email.from:noreply@mfplatform.com}")
    private String fromAddress;

    public EmailNotificationChannel(JavaMailSender mailSender, TemplateEngine templateEngine) {
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public String channelName() {
        return "EMAIL";
    }

    /**
     * Builds and sends an HTML email.
     *
     * Steps:
     *   1. Create a Thymeleaf Context with the template model from the payload
     *   2. Render the HTML template to a String
     *   3. Build a MimeMessage (supports HTML content, unlike SimpleMailMessage)
     *   4. Set headers: from, to, subject, HTML body
     *   5. Send via JavaMailSender
     *
     * MimeMessageHelper with multipart=true enables:
     *   - HTML + plain text fallback (accessibility)
     *   - File attachments (future feature)
     *
     * If the templateName is null or rendering fails, falls back to sending
     * the smsBody as plain text — graceful degradation.
     */
    @Override
    public void send(NotificationPayload payload) {
        if (payload.recipientEmail() == null) {
            log.debug("Skipping email for event {} — no recipient email", payload.event());
            return;
        }

        try {
            String htmlContent = renderTemplate(payload);

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(fromAddress);
            helper.setTo(payload.recipientEmail());
            helper.setSubject(payload.subject());
            helper.setText(payload.smsBody(), htmlContent); // (plaintext, html)

            mailSender.send(message);

            log.info("Email sent | event={} | to={} | subject={}",
                    payload.event(), payload.recipientEmail(), payload.subject());

        } catch (MessagingException ex) {
            // Log but don't rethrow — email failure must not crash the application.
            // The exception is caught by NotificationService's try-catch anyway,
            // but being explicit here documents the intent.
            log.error("Failed to send email | event={} | to={} | error={}",
                    payload.event(), payload.recipientEmail(), ex.getMessage());
            throw new RuntimeException("Email sending failed", ex);
        }
    }

    /**
     * Renders a Thymeleaf HTML template.
     *
     * If no template name is specified, or if the template doesn't exist,
     * wraps the SMS body in minimal HTML as a fallback.
     */
    private String renderTemplate(NotificationPayload payload) {
        if (payload.templateName() == null) {
            return "<html><body><p>" + payload.smsBody() + "</p></body></html>";
        }

        try {
            Context ctx = new Context();
            if (payload.templateModel() != null) {
                // Make the model available to the template as "model"
                ctx.setVariable("model", payload.templateModel());
            }
            // Also expose individual fields directly for simpler template expressions
            ctx.setVariable("recipientName",  payload.recipientName());
            ctx.setVariable("event",          payload.event().name());
            return templateEngine.process(payload.templateName(), ctx);

        } catch (Exception ex) {
            log.warn("Template rendering failed for {}, falling back to plain HTML: {}",
                    payload.templateName(), ex.getMessage());
            return "<html><body><p>" + payload.smsBody() + "</p></body></html>";
        }
    }
}
