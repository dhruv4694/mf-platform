package com.mfplatform.mfplatform.transaction;

/**
 * TransactionType distinguishes the two kinds of transactions.
 *
 * PURCHASE  — investor sends money to the fund house, receives units
 * REDEMPTION — investor sends units back, receives money
 *
 * This is stored as a STRING in the DB (not ordinal) via @Enumerated(EnumType.STRING)
 * so that reordering enum constants never corrupts existing data.
 *
 * Note: SIP-originated purchases are still PURCHASE type — the sip_mandate_id
 * FK on MfTransaction is what identifies them as SIP-originated, not a
 * separate type. See ADR-006.
 */
public enum TransactionType {
    PURCHASE,
    REDEMPTION
}
