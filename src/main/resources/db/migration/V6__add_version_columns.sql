-- V6__add_version_columns.sql
--
-- MfTransaction entity has @Version private Long version for optimistic locking.
-- This column was missing from the mf_transaction table — Hibernate validate
-- detected the gap and refused to start.
-- Holding already has version from V1. No action needed there.

ALTER TABLE mf_transaction
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;