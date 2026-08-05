package com.mfplatform.mfplatform.transaction;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Payment tracks the payment realization status for a purchase transaction.
 *
 * In a real AMC system, payments arrive via:
 *   - NetBanking / UPI: near-instant realization (same day)
 *   - RTGS/NEFT: same-day or next-day depending on time of transfer
 *   - Cheque: 2-3 business days clearing time
 *
 * The realized_at timestamp is critical — it's what NavEligibilityService
 * uses to determine whether same-day NAV applies (payment must be realized
 * before the cutoff time, not just initiated).
 *
 * Only PURCHASE transactions have a Payment record — redemptions don't
 * require incoming payment, they generate outgoing payment to the investor
 * (modelled separately, out of scope for this project).
 */
@Entity
@Table(name = "payment")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The purchase transaction this payment is for.
     * One-to-one in practice — each purchase has exactly one payment attempt.
     */
    @Column(name = "transaction_id", nullable = false, updatable = false)
    private Long transactionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    /**
     * When the payment was initiated (investor clicked "Pay").
     */
    @Column(name = "initiated_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant initiatedAt = Instant.now();

    /**
     * When the payment was confirmed as cleared/received by the fund house.
     * Null until realization.
     */
    @Column(name = "realized_at")
    private Instant realizedAt;

    // ─── Domain methods ───────────────────────────────────────────────────────

    public void markRealized() {
        this.status = PaymentStatus.REALIZED;
        this.realizedAt = Instant.now();
    }

    public void markFailed() {
        this.status = PaymentStatus.FAILED;
    }

    public boolean isRealized() {
        return this.status == PaymentStatus.REALIZED;
    }
}
