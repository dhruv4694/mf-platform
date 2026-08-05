package com.mfplatform.mfplatform.transaction;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * PaymentSimulationService simulates a payment gateway for purchase transactions.
 *
 * IN A REAL AMC SYSTEM:
 * The fund house integrates with a payment gateway (BillDesk, Razorpay, etc.)
 * which sends a callback (webhook) when payment is confirmed. The callback
 * handler would call markRealized() and trigger NAV eligibility resolution.
 *
 * IN THIS PROJECT:
 * We simulate two behaviors:
 *   1. simulateInstantRealization() — payment clears immediately (NetBanking/UPI)
 *      This is the "happy path" used by most test scenarios.
 *   2. simulateFailure() — payment bounces
 *
 * WHY PAYMENT IS A SEPARATE ENTITY FROM TRANSACTION:
 * A transaction represents the investor's intent ("I want to buy ₹5000 of XYZ").
 * A payment represents the money movement ("the ₹5000 arrived in the fund's account").
 * These are different concerns — the transaction exists even if payment fails,
 * and in theory a transaction could be paid via multiple instruments
 * (though we don't model that here).
 *
 * IMPORTANT — realized_at timestamp:
 * NavEligibilityService reads payment.realizedAt to determine whether
 * same-day NAV applies. The exact time of realization matters:
 *   - Realized at 2:30 PM → before 3 PM cutoff → same-day NAV (for equity)
 *   - Realized at 3:30 PM → after 3 PM cutoff → next-day NAV
 * simulateInstantRealization() uses Instant.now() so tests run at different
 * times of day will naturally exercise both NAV eligibility paths.
 */
@Service
public class PaymentSimulationService {

    private final PaymentRepository paymentRepository;
    private final MfTransactionRepository transactionRepository;

    public PaymentSimulationService(
            PaymentRepository paymentRepository,
            MfTransactionRepository transactionRepository) {
        this.paymentRepository = paymentRepository;
        this.transactionRepository = transactionRepository;
    }

    /**
     * Initiates a payment record for a purchase transaction.
     * Called by PurchaseService immediately after the transaction row is created.
     *
     * Creates a Payment row in INITIATED state. The transaction stays PENDING
     * until payment is realized.
     *
     * @param transactionId the MfTransaction to create payment for
     * @return the saved Payment entity
     */
    @Transactional
    public Payment initiatePayment(Long transactionId) {
        return paymentRepository.save(
                Payment.builder()
                        .transactionId(transactionId)
                        .status(PaymentStatus.INITIATED)
                        .initiatedAt(Instant.now())
                        .build()
        );
    }

    /**
     * Simulates instant payment realization (NetBanking / UPI scenario).
     *
     * In a real system this would be called by a webhook handler when the
     * payment gateway confirms the payment cleared.
     *
     * Flow:
     *   1. Mark payment as REALIZED, record realized_at timestamp
     *   2. Transition transaction from PENDING → PAYMENT_REALIZED
     *   Both in one @Transactional boundary.
     *
     * The realized_at timestamp is used by NavEligibilityService — this is
     * the moment the fund house "has the money" and can apply a NAV.
     *
     * @param transactionId the transaction whose payment has been realized
     * @return the updated Payment with realized_at set
     */
    @Transactional
    public Payment simulateInstantRealization(Long transactionId) {
        Payment payment = paymentRepository.findByTransactionId(transactionId)
                .orElseThrow(() -> new IllegalStateException(
                        "No payment record found for transaction " + transactionId));

        // Mark the payment as realized
        payment.markRealized();
        paymentRepository.save(payment);

        // Advance the transaction status
        MfTransaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new IllegalStateException(
                        "Transaction not found: " + transactionId));
        transaction.transitionTo(TransactionStatus.PAYMENT_REALIZED);
        transactionRepository.save(transaction);

        return payment;
    }

    /**
     * Simulates a payment failure (bounced cheque / gateway timeout).
     *
     * Not currently called by any pipeline — EodProcessingService settles
     * every PENDING PURCHASE uniformly via simulateInstantRealization(),
     * including SIP installments (see SipItemProcessor). Kept as a documented
     * capability of the payment simulation layer.
     *
     * Also used in tests to verify the failure path.
     *
     * @param transactionId the transaction whose payment has failed
     */
    @Transactional
    public void simulateFailure(Long transactionId) {
        Payment payment = paymentRepository.findByTransactionId(transactionId)
                .orElseThrow(() -> new IllegalStateException(
                        "No payment record found for transaction " + transactionId));

        // Mark payment as failed
        payment.markFailed();
        paymentRepository.save(payment);

        // Terminal transition: transaction → FAILED
        MfTransaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new IllegalStateException(
                        "Transaction not found: " + transactionId));
        transaction.markFailed();
        transactionRepository.save(transaction);
    }

    /**
     * Retrieves the payment record for a transaction.
     * Used by NavEligibilityService to read payment.realizedAt.
     */
    public Payment getPaymentForTransaction(Long transactionId) {
        return paymentRepository.findByTransactionId(transactionId)
                .orElseThrow(() -> new IllegalStateException(
                        "No payment record found for transaction " + transactionId));
    }
}
