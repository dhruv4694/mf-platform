-- V2__add_transaction_fields.sql
-- Adds fields required by the transaction validation pipeline and State pattern.
-- These were missed in V1 since the transaction module was designed after initial schema.

-- Investor: KYC status flag
ALTER TABLE investor
    ADD COLUMN IF NOT EXISTS kyc_complete BOOLEAN NOT NULL DEFAULT FALSE;

-- Scheme: open/close flags, minimum purchase amount, lock-in date
ALTER TABLE scheme
    ADD COLUMN IF NOT EXISTS open_for_purchase      BOOLEAN        NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS open_for_redemption    BOOLEAN        NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS minimum_purchase_amount NUMERIC(12,2) NOT NULL DEFAULT 1000.00,
    ADD COLUMN IF NOT EXISTS lock_in_end_date        DATE;

-- Rename transaction table to mf_transaction (transaction is a reserved SQL keyword)
-- Note: this also renames all FK constraints and indexes that reference it
ALTER TABLE transaction RENAME TO mf_transaction;

-- Update the self-referential FK on the renamed table
-- (PostgreSQL renames the constraint automatically on table rename, but
--  we explicitly drop and re-add for clarity and portability)
ALTER TABLE mf_transaction
    DROP CONSTRAINT IF EXISTS transaction_reversal_of_id_fkey;

ALTER TABLE mf_transaction
    ADD CONSTRAINT mf_transaction_reversal_of_id_fkey
        FOREIGN KEY (reversal_of_id) REFERENCES mf_transaction(id);

-- Add ALLOTMENT_IN_PROGRESS to the status check constraint
-- (drop and recreate since PostgreSQL doesn't support ALTER on CHECK constraints)
ALTER TABLE mf_transaction
    DROP CONSTRAINT IF EXISTS transaction_status_check;

ALTER TABLE mf_transaction
    ADD CONSTRAINT mf_transaction_status_check
        CHECK (status IN (
            'PENDING',
            'PAYMENT_REALIZED',
            'NAV_APPLIED',
            'ALLOTMENT_IN_PROGRESS',
            'ALLOTTED',
            'FAILED',
            'REVERSED'
        ));

-- Update sip_mandate FK to point to renamed table
ALTER TABLE sip_mandate
    DROP CONSTRAINT IF EXISTS sip_mandate_transaction_id_fkey;

-- payment table FK update
ALTER TABLE payment
    DROP CONSTRAINT IF EXISTS payment_transaction_id_fkey;

ALTER TABLE payment
    ADD CONSTRAINT payment_transaction_id_fkey
        FOREIGN KEY (transaction_id) REFERENCES mf_transaction(id);

-- notification table FK update
ALTER TABLE notification
    DROP CONSTRAINT IF EXISTS notification_transaction_id_fkey;

ALTER TABLE notification
    ADD CONSTRAINT notification_transaction_id_fkey
        FOREIGN KEY (transaction_id) REFERENCES mf_transaction(id);

-- Add initiated_by_role column (was missing from V1 CHECK constraint)
ALTER TABLE mf_transaction
    DROP CONSTRAINT IF EXISTS transaction_initiated_by_role_check;

ALTER TABLE mf_transaction
    ADD CONSTRAINT mf_transaction_initiated_by_role_check
        CHECK (initiated_by_role IN ('INVESTOR', 'DISTRIBUTOR', 'ADMIN'));

-- Add index on status for the claim query and pending-transaction lookups
CREATE INDEX IF NOT EXISTS idx_mf_transaction_status
    ON mf_transaction(status);

-- Add index on scheme_id + status for NavImportedEvent listener query
CREATE INDEX IF NOT EXISTS idx_mf_transaction_scheme_status
    ON mf_transaction(scheme_id, status);
