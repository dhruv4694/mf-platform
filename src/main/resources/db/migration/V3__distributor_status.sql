-- V3__distributor_status.sql
-- Adds status lifecycle to the distributor table to support public self-signup
-- with background ARN verification simulation.

ALTER TABLE distributor
    ADD COLUMN IF NOT EXISTS status       VARCHAR(30)  NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN IF NOT EXISTS submitted_at TIMESTAMP    NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS verified_at  TIMESTAMP;

-- Add CHECK constraint for valid status values
ALTER TABLE distributor
    ADD CONSTRAINT distributor_status_check
        CHECK (status IN (
            'PENDING_VERIFICATION',
            'ACTIVE',
            'REJECTED',
            'SUSPENDED'
        ));

-- Existing distributor rows (created by DataSeeder or earlier migrations)
-- default to ACTIVE — they were created by the admin so they're verified.
UPDATE distributor SET status = 'ACTIVE' WHERE status = 'ACTIVE';

-- Index for the background worker query:
-- findByStatusAndSubmittedAtBefore runs every 10 seconds
CREATE INDEX IF NOT EXISTS idx_distributor_status_submitted
    ON distributor(status, submitted_at)
    WHERE status = 'PENDING_VERIFICATION';
