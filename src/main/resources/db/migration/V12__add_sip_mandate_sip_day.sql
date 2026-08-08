-- frequency's CHECK constraint (V1) already allows 'QUARTERLY' alongside
-- 'WEEKLY'/'MONTHLY', so no widening needed there. sip_day is the new
-- MONTHLY-only recurring deduction day (1-31), null for WEEKLY/QUARTERLY.
ALTER TABLE sip_mandate
    ADD COLUMN IF NOT EXISTS sip_day INTEGER NULL;
