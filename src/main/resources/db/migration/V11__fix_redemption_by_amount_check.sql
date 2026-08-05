-- V1's original CHECK constraint required REDEMPTION rows to always have
-- request_units set, but RedemptionService has always supported "redemption
-- by amount" (request_units null, request_amount set instead) — the DB
-- constraint never matched that documented behavior, so any amount-mode
-- redemption has always failed at insert time with a check constraint
-- violation. Widen it to accept either mode for REDEMPTION, matching
-- RedemptionService.validateRedemptionMode() (exactly one of units/amount,
-- enforced at the application layer).
ALTER TABLE mf_transaction
    DROP CONSTRAINT IF EXISTS transaction_check;

ALTER TABLE mf_transaction
    ADD CONSTRAINT mf_transaction_amount_or_units_check
    CHECK (
        (type = 'PURCHASE'   AND request_amount IS NOT NULL) OR
        (type = 'REDEMPTION' AND (request_units IS NOT NULL OR request_amount IS NOT NULL))
    );
