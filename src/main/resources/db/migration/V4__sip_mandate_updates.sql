-- V4__sip_mandate_updates.sql
-- Adds initiated_by_user_id to sip_mandate (needed to set the same field
-- on the PURCHASE transaction the SIP creates).
-- Also adds the partial index used by SipExecutionService's due-mandate query.

ALTER TABLE sip_mandate
    ADD COLUMN IF NOT EXISTS initiated_by_user_id BIGINT REFERENCES user_account(id);

-- Partial index: SipExecutionService runs every morning and queries
-- WHERE status = 'ACTIVE' AND next_due_date <= today.
-- Indexing only ACTIVE mandates keeps the index small even with many
-- CANCELLED/COMPLETED/PAUSED mandates accumulating over time.
CREATE INDEX IF NOT EXISTS idx_sip_active_due_date
    ON sip_mandate(next_due_date)
    WHERE status = 'ACTIVE';
