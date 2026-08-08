package com.mfplatform.mfplatform.sip;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;
import java.time.YearMonth;

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
     * The recurring day-of-month deductions land on — MONTHLY only, null for
     * WEEKLY/QUARTERLY (those use fixed calendar anchors, no user-chosen day).
     * Independent of startDate: startDate is when the mandate becomes active,
     * sipDay is which day of the month every installment (including the
     * first) is scheduled for. Clamped to the target month's actual length
     * when it exceeds it (e.g. sipDay=31 in February → the 28th/29th).
     */
    @Column(name = "sip_day")
    private Integer sipDay;

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
     * Called by the SIP batch (SipItemProcessor) after each installment —
     * regardless of whether the installment succeeded or failed.
     *
     * Fixed-calendar-anchor based, not incremental day/month arithmetic —
     * incremental arithmetic (plusWeeks(1), plusMonths(1)) is exactly what
     * breaks at month/year boundaries (e.g. day 28 + 7 days lands on the 4th/5th
     * of next month instead of the fixed 7th anchor). See computeFirstDueDate()
     * and nextAnchorOnOrAfter() for the shared anchor logic.
     *
     * After advancing, checks if the new nextDueDate has passed the endDate.
     * If so, marks the mandate COMPLETED.
     */
    public void advanceNextDueDate() {
        this.nextDueDate = nextAnchorOnOrAfter(this.nextDueDate.plusDays(1), this.frequency, this.sipDay);

        // Auto-complete if end date has passed
        if (this.endDate != null && this.nextDueDate.isAfter(this.endDate)) {
            this.status = SipMandateStatus.COMPLETED;
        }
    }

    /**
     * Computes the first installment date for a new mandate — the nearest
     * schedule anchor on or after startDate. Used by SipMandateService at
     * registration time.
     *
     * MONTHLY: if startDate's day-of-month <= sipDay, the first due date is
     * sipDay in startDate's own month (clamped to that month's length);
     * otherwise it's sipDay in the following month. This is exactly what
     * nextAnchorOnOrAfter() computes when seeded with startDate itself —
     * "the next MONTHLY anchor on or after startDate" is the same question
     * as "the first due date."
     *
     * WEEKLY/QUARTERLY: nearest fixed anchor (7/14/21/28, or Jan/Apr/Jul/Oct
     * 8th) on or after startDate — keeps the first installment consistent
     * with the mandate's own scheduleDescription rather than landing on an
     * arbitrary startDate that isn't one of the advertised anchor days.
     *
     * @param sipDay required for MONTHLY, ignored for WEEKLY/QUARTERLY
     */
    public static LocalDate computeFirstDueDate(LocalDate startDate, SipFrequency frequency, Integer sipDay) {
        return nextAnchorOnOrAfter(startDate, frequency, sipDay);
    }

    /**
     * The single shared anchor-resolution routine both advanceNextDueDate()
     * and computeFirstDueDate() delegate to — "find the next schedule anchor
     * on or after the given reference date." advanceNextDueDate() seeds it
     * with (currentNextDueDate + 1 day) to guarantee strict forward progress;
     * computeFirstDueDate() seeds it with startDate itself (inclusive — the
     * first installment can land exactly on startDate if that's already an
     * anchor).
     */
    private static LocalDate nextAnchorOnOrAfter(LocalDate ref, SipFrequency frequency, Integer sipDay) {
        return switch (frequency) {
            case WEEKLY -> nextWeeklyAnchor(ref);
            case QUARTERLY -> nextQuarterlyAnchor(ref);
            case MONTHLY -> nextMonthlyAnchor(ref, sipDay);
        };
    }

    /** Fixed anchors: 7th, 14th, 21st, 28th of every month. */
    private static LocalDate nextWeeklyAnchor(LocalDate ref) {
        int[] anchors = {7, 14, 21, 28};
        for (int anchor : anchors) {
            if (anchor >= ref.getDayOfMonth()) {
                return ref.withDayOfMonth(anchor);
            }
        }
        // Past the 28th this month — wrap to the 7th of next month.
        return ref.plusMonths(1).withDayOfMonth(7);
    }

    /** Fixed anchors: 8th of January, April, July, October. */
    private static LocalDate nextQuarterlyAnchor(LocalDate ref) {
        int[] anchorMonths = {1, 4, 7, 10};
        for (int month : anchorMonths) {
            LocalDate candidate = LocalDate.of(ref.getYear(), month, 8);
            if (!candidate.isBefore(ref)) {
                return candidate;
            }
        }
        // Past October 8th this year — wrap to January 8th of next year.
        return LocalDate.of(ref.getYear() + 1, 1, 8);
    }

    /** User-chosen day-of-month anchor, clamped to each target month's actual length. */
    private static LocalDate nextMonthlyAnchor(LocalDate ref, int sipDay) {
        LocalDate candidateThisMonth = clampToMonth(ref.getYear(), ref.getMonthValue(), sipDay);
        if (!candidateThisMonth.isBefore(ref)) {
            return candidateThisMonth;
        }
        LocalDate nextMonth = ref.plusMonths(1);
        return clampToMonth(nextMonth.getYear(), nextMonth.getMonthValue(), sipDay);
    }

    /**
     * Clamps day to the actual length of the given year/month — e.g. sipDay=31
     * in February becomes the 28th (or 29th in a leap year). Standard AMC SIP
     * behavior: the deduction still happens that month, just on the last valid day.
     */
    private static LocalDate clampToMonth(int year, int month, int day) {
        YearMonth yearMonth = YearMonth.of(year, month);
        int clampedDay = Math.min(day, yearMonth.lengthOfMonth());
        return yearMonth.atDay(clampedDay);
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
