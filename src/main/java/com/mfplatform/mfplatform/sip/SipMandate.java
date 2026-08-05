package com.mfplatform.mfplatform.sip;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;

/**
 * SipMandate is a standing instruction that generates PURCHASE transactions
 * on a recurring schedule.
 *
 * DESIGN (see ADR-006):
 * A SIP is NOT a transaction type — it's an origination mechanism.
 * Each SIP installment creates a regular PURCHASE transaction with
 * sip_mandate_id set, then flows through the exact same pipeline as
 * a manual purchase (validation → payment → NAV → allotment).
 *
 * KEY FIELDS:
 *
 * nextDueDate — the date of the next scheduled installment.
 *   The SipExecutionService queries: WHERE next_due_date <= today AND status = ACTIVE
 *   After processing (success OR failure), nextDueDate is advanced by the frequency.
 *   This means a failed installment does NOT break the schedule — the next
 *   attempt happens on time regardless.
 *
 * endDate — optional. null = open-ended SIP (runs indefinitely).
 *   When nextDueDate > endDate, the mandate is automatically moved to COMPLETED.
 *
 * mandateReference — simulated bank mandate ID (like NACH mandate reference).
 *   In a real system this would be returned by the bank after mandate registration.
 *   Here we generate a fake reference at registration time.
 *
 * RELATIONSHIP TO MfTransaction:
 * Every installment creates a MfTransaction with:
 *   type = PURCHASE
 *   sip_mandate_id = this.id
 *   request_amount = this.amount
 * The transaction's pipeline is identical to a manual purchase.
 */
@Entity
@Table(name = "sip_mandate")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SipMandate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "folio_id", nullable = false, updatable = false)
    private Long folioId;

    @Column(name = "scheme_id", nullable = false, updatable = false)
    private Long schemeId;

    /**
     * Fixed INR amount per installment. e.g. ₹2000 every month.
     * Stored immutably — changing the SIP amount requires cancelling and
     * creating a new mandate (same as real NACH mandate modification rules).
     */
    @Column(nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private SipFrequency frequency;

    @Column(name = "start_date", nullable = false, updatable = false)
    private LocalDate startDate;

    /**
     * Optional — null means the SIP runs indefinitely until cancelled.
     * When nextDueDate passes endDate, status moves to COMPLETED automatically.
     */
    @Column(name = "end_date", updatable = false)
    private LocalDate endDate;

    /**
     * The date of the next installment to process.
     * Initialised to startDate at registration.
     * Advanced by SipExecutionService after each installment (success or failure).
     * This is the only mutable date field — everything else is immutable.
     */
    @Column(name = "next_due_date", nullable = false)
    private LocalDate nextDueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private SipMandateStatus status = SipMandateStatus.ACTIVE;

    /**
     * Simulated bank mandate reference number (NACH mandate ID in real systems).
     * Generated at registration — proves the mandate was "registered with the bank."
     */
    @Column(name = "mandate_reference", nullable = false, updatable = false)
    private String mandateReference;

    @Column(name = "initiated_by_user_id", nullable = false, updatable = false)
    private Long initiatedByUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    // ─── Domain methods ───────────────────────────────────────────────────────

    /**
     * Advances nextDueDate to the next installment date based on frequency.
     * Called by SipExecutionService after each installment — regardless of
     * whether the installment succeeded or failed.
     *
     * After advancing, checks if the new nextDueDate has passed the endDate.
     * If so, marks the mandate COMPLETED.
     */
    public void advanceNextDueDate() {
        this.nextDueDate = switch (this.frequency) {
            case WEEKLY     -> this.nextDueDate.plusWeeks(1);
            case MONTHLY    -> this.nextDueDate.plusMonths(1);
            case QUARTERLY  -> this.nextDueDate.plusMonths(3);
        };

        // Auto-complete if end date has passed
        if (this.endDate != null && this.nextDueDate.isAfter(this.endDate)) {
            this.status = SipMandateStatus.COMPLETED;
        }
    }

    /**
     * Pauses the SIP. Investor can resume later.
     * SipExecutionService will skip PAUSED mandates.
     */
    public void pause() {
        if (this.status != SipMandateStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Can only pause an ACTIVE mandate. Current status: " + this.status);
        }
        this.status = SipMandateStatus.PAUSED;
    }

    /**
     * Resumes a paused SIP.
     */
    public void resume() {
        if (this.status != SipMandateStatus.PAUSED) {
            throw new IllegalStateException(
                    "Can only resume a PAUSED mandate. Current status: " + this.status);
        }
        this.status = SipMandateStatus.ACTIVE;
    }

    /**
     * Permanently cancels the SIP. Irreversible.
     */
    public void cancel() {
        if (this.status == SipMandateStatus.COMPLETED ||
                this.status == SipMandateStatus.CANCELLED) {
            throw new IllegalStateException(
                    "Cannot cancel a mandate in status: " + this.status);
        }
        this.status = SipMandateStatus.CANCELLED;
    }

    public boolean isActive() {
        return this.status == SipMandateStatus.ACTIVE;
    }
}
