package com.mfplatform.mfplatform.aspect;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @Auditable marks a method as one that should produce a structured audit
 * log entry every time it is called.
 *
 * WHAT GETS LOGGED:
 *   - WHO:    the caller's username and role (from SecurityContext)
 *   - WHAT:   the operation name (from this annotation's 'operation' field)
 *   - WHEN:   timestamp (ISO-8601)
 *   - RESULT: SUCCESS or FAILURE, with reason on failure
 *
 * WHERE THE LOG GOES:
 *   A dedicated rolling log file: logs/audit.log
 *   Rotated daily, kept for 90 days (configurable in logback-spring.xml).
 *   Separate from the application log — audit entries are never mixed with
 *   debug noise, and the audit file can be monitored/archived independently.
 *
 * USAGE:
 *   @Auditable(operation = "PURCHASE")
 *   public TransactionResponse createPurchase(...) { ... }
 *
 *   @Auditable(operation = "SIP_REGISTRATION")
 *   public SipMandateResponse register(...) { ... }
 *
 * The 'operation' string becomes the event type in the log line:
 *   {"timestamp":"2026-07-14T09:00:00Z","operation":"PURCHASE","actor":"priya.sharma",
 *    "role":"INVESTOR","result":"SUCCESS","details":"transactionId=42"}
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Auditable {

    /**
     * Human-readable name for the operation being audited.
     * Used as the 'operation' field in the audit log entry.
     * Use SCREAMING_SNAKE_CASE convention: "PURCHASE", "REDEMPTION",
     * "SIP_REGISTRATION", "SIP_CANCEL", "DISTRIBUTOR_SIGNUP" etc.
     */
    String operation();
}
