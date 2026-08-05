package com.mfplatform.mfplatform.notification;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Notification records every notification sent — email or SMS.
 *
 * PURPOSE:
 *   1. Audit trail: "was the welcome email actually sent to this investor?"
 *   2. Notification history for the investor dashboard (future feature)
 *   3. Debugging: when did the SIP bounce notification go out?
 *
 * This table already exists in V1__init_schema.sql.
 * NotificationService saves a row here AFTER successfully sending through
 * at least one channel — not before (we don't want phantom records for
 * notifications that were never actually sent).
 */
@Entity
@Table(name = "notification")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The investor this notification was sent to.
     * Nullable because distributor notifications don't have an investor.
     */
    @Column(name = "investor_id")
    private Long investorId;

    /**
     * The transaction this notification relates to, if any.
     * e.g. "Your purchase of ₹5000 has been allotted" → links to that transaction.
     * Null for account-level notifications (welcome email, KYC reminder).
     */
    @Column(name = "transaction_id")
    private Long transactionId;

    /**
     * Human-readable summary of what was sent.
     * e.g. "Welcome email sent via EMAIL to priya@example.com"
     * Used for the notification history view and for debugging.
     */
    @Column(nullable = false)
    private String message;

    @Column(name = "sent_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant sentAt = Instant.now();
}
