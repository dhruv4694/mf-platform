package com.mfplatform.mfplatform.transaction;

/**
 * PaymentStatus tracks the lifecycle of a payment.
 *
 * INITIATED  — investor has submitted payment, awaiting clearance
 * REALIZED   — payment confirmed received by the fund house
 *              (this timestamp drives NAV eligibility)
 * FAILED     — payment bounced or timed out
 *              (transaction moves to FAILED, investor is notified)
 */
public enum PaymentStatus {
    INITIATED,
    REALIZED,
    FAILED
}
