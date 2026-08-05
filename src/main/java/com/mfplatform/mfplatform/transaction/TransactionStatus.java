package com.mfplatform.mfplatform.transaction;

/**
 * TransactionStatus implements the State pattern for transaction lifecycle management.
 *
 * THE STATE PATTERN:
 * Each enum constant overrides transitionTo() to define which states it can
 * move to. Attempting an invalid transition throws InvalidTransactionStateException
 * immediately — the error is caught at the enum level, not buried in a service.
 *
 * LIFECYCLE:
 *
 *   PENDING
 *     ↓  (PaymentSimulationService confirms payment)
 *   PAYMENT_REALIZED
 *     ↓  (EodProcessingService applies the business-date NAV)
 *   NAV_APPLIED
 *     ↓  (UnitAllotmentService claims the transaction — compare-and-set)
 *   ALLOTMENT_IN_PROGRESS
 *     ↓  (UnitAllotmentService completes holding update)
 *   ALLOTTED  ──────────────────────────────────────────────── terminal (happy path)
 *     ↓  (only if correction needed — new row with reversal_of_id)
 *   REVERSED ──────────────────────────────────────────────── terminal
 *
 *   Any state → FAILED ──────────────────────────────────────── terminal (error path)
 *
 * WHY ALLOTMENT_IN_PROGRESS:
 * In a multi-instance deployment, multiple workers might pick up the same
 * NAV_APPLIED transaction. The compare-and-set UPDATE (WHERE status = NAV_APPLIED)
 * atomically moves it to ALLOTMENT_IN_PROGRESS. Only the worker that wins this
 * claim proceeds. Others see claimed = 0 and exit immediately — before touching
 * the Holding table. See ADR-003 for full reasoning.
 *
 * TERMINAL STATES:
 * ALLOTTED, REVERSED, and FAILED cannot transition to anything. Any attempt
 * throws InvalidTransactionStateException.
 */
public enum TransactionStatus {

    PENDING {
        @Override
        public TransactionStatus transitionTo(TransactionStatus next) {
            if (next == PAYMENT_REALIZED || next == FAILED) return next;
            throw new InvalidTransactionStateException(this, next);
        }
    },

    PAYMENT_REALIZED {
        @Override
        public TransactionStatus transitionTo(TransactionStatus next) {
            if (next == NAV_APPLIED || next == FAILED) return next;
            throw new InvalidTransactionStateException(this, next);
        }
    },

    NAV_APPLIED {
        @Override
        public TransactionStatus transitionTo(TransactionStatus next) {
            // ALLOTMENT_IN_PROGRESS is the claim step — see ADR-003
            if (next == ALLOTMENT_IN_PROGRESS || next == FAILED) return next;
            throw new InvalidTransactionStateException(this, next);
        }
    },

    ALLOTMENT_IN_PROGRESS {
        @Override
        public TransactionStatus transitionTo(TransactionStatus next) {
            if (next == ALLOTTED || next == FAILED) return next;
            throw new InvalidTransactionStateException(this, next);
        }
    },

    ALLOTTED {
        @Override
        public TransactionStatus transitionTo(TransactionStatus next) {
            // Only valid transition from ALLOTTED is REVERSED (for corrections)
            // Reversals create a NEW transaction row — they don't edit this one.
            // The reversal_of_id FK on the new row links back to this transaction.
            if (next == REVERSED) return next;
            throw new InvalidTransactionStateException(this, next);
        }
    },

    FAILED {
        @Override
        public TransactionStatus transitionTo(TransactionStatus next) {
            // Terminal state — no transitions allowed
            throw new InvalidTransactionStateException(this, next);
        }
    },

    REVERSED {
        @Override
        public TransactionStatus transitionTo(TransactionStatus next) {
            // Terminal state — no transitions allowed
            throw new InvalidTransactionStateException(this, next);
        }
    };

    /**
     * Attempts to transition to the given next status.
     *
     * @param next the desired next status
     * @return the next status (for fluent assignment: this.status = status.transitionTo(next))
     * @throws InvalidTransactionStateException if the transition is not valid
     */
    public abstract TransactionStatus transitionTo(TransactionStatus next);

    /**
     * Whether this status is a terminal state (no further transitions possible).
     * Useful for guards in the allotment pipeline.
     */
    public boolean isTerminal() {
        return this == ALLOTTED || this == FAILED || this == REVERSED;
    }
}
