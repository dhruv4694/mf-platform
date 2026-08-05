package com.mfplatform.mfplatform.sip;

/**
 * SipMandateStatus tracks the lifecycle of a SIP mandate.
 *
 * ACTIVE    — mandate is live, installments will be created on each due date
 * PAUSED    — investor paused the SIP, installments are skipped but next_due_date
 *             still advances so resuming picks up the correct schedule
 * CANCELLED — investor cancelled the SIP permanently, no more installments
 * COMPLETED — end_date has passed and all installments are done
 *
 * PAUSED vs CANCELLED:
 * PAUSED is reversible — investor can resume. CANCELLED is permanent.
 * The SIP batch job's due-mandate query skips both PAUSED and CANCELLED
 * mandates — it only selects ACTIVE ones.
 */
public enum SipMandateStatus {
    ACTIVE,
    PAUSED,
    CANCELLED,
    COMPLETED
}
