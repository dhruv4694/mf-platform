-- V1 created mf_transaction.status as VARCHAR(20). V2 added ALLOTMENT_IN_PROGRESS
-- (21 characters) to the CHECK constraint but never widened the column itself,
-- so any transaction reaching the claim step fails with
-- "value too long for type character varying(20)" — a pre-existing bug that
-- blocked the allotment pipeline entirely (caught only by an actual end-to-end
-- run against a real database; Mockito-based unit tests never exercise this).
ALTER TABLE mf_transaction
    ALTER COLUMN status TYPE VARCHAR(30);
