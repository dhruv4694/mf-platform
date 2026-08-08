package com.mfplatform.mfplatform.sip;

/**
 * SipFrequency defines how often a SIP installment is executed.
 *
 * All three use fixed calendar anchors (see SipMandate.advanceNextDueDate()),
 * not incremental date arithmetic:
 *   WEEKLY    — 7th, 14th, 21st, 28th of every month
 *   MONTHLY   — a user-chosen day-of-month (SipMandate.sipDay), clamped to
 *               each target month's actual length
 *   QUARTERLY — 8th of January, April, July, October
 *
 * SipItemProcessor's mandate advances next_due_date after each installment
 * is created, via SipMandate.advanceNextDueDate().
 */
public enum SipFrequency {
    WEEKLY,
    MONTHLY,
    QUARTERLY
}
