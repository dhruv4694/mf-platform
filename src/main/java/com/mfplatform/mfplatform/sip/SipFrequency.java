package com.mfplatform.mfplatform.sip;

/**
 * SipFrequency defines how often a SIP installment is executed.
 *
 * WEEKLY    — every 7 days from the start date
 * MONTHLY   — same date each month (e.g. 5th of every month)
 * QUARTERLY — same date every 3 months
 *
 * SipItemProcessor uses this to advance next_due_date after each
 * installment is created.
 */
public enum SipFrequency {
    WEEKLY,
    MONTHLY,
    QUARTERLY
}
