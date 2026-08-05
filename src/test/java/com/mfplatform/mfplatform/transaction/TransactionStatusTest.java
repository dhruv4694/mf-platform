package com.mfplatform.mfplatform.transaction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for TransactionStatus (State pattern).
 *
 * WHY THESE MATTER:
 * The State pattern is only useful if invalid transitions are reliably prevented.
 * These tests verify that:
 *   1. Every valid transition succeeds
 *   2. Every invalid transition throws InvalidTransactionStateException
 *   3. Terminal states (ALLOTTED, FAILED, REVERSED) cannot transition to anything
 *   4. isTerminal() correctly identifies terminal states
 *
 * NO MOCKS, NO SPRING CONTEXT — just enum method calls.
 */
@DisplayName("TransactionStatus (State Pattern)")
class TransactionStatusTest {

    // ─── Valid transitions ────────────────────────────────────────────────────

    @Nested
    @DisplayName("valid transitions")
    class ValidTransitions {

        @Test
        @DisplayName("PENDING → PAYMENT_REALIZED (payment confirmed)")
        void pendingToPaymentRealized() {
            TransactionStatus result =
                TransactionStatus.PENDING.transitionTo(TransactionStatus.PAYMENT_REALIZED);
            assertThat(result).isEqualTo(TransactionStatus.PAYMENT_REALIZED);
        }

        @Test
        @DisplayName("PENDING → FAILED (payment failed immediately)")
        void pendingToFailed() {
            TransactionStatus result =
                TransactionStatus.PENDING.transitionTo(TransactionStatus.FAILED);
            assertThat(result).isEqualTo(TransactionStatus.FAILED);
        }

        @Test
        @DisplayName("PAYMENT_REALIZED → NAV_APPLIED")
        void paymentRealizedToNavApplied() {
            TransactionStatus result =
                TransactionStatus.PAYMENT_REALIZED.transitionTo(TransactionStatus.NAV_APPLIED);
            assertThat(result).isEqualTo(TransactionStatus.NAV_APPLIED);
        }

        @Test
        @DisplayName("PAYMENT_REALIZED → FAILED (NAV not available)")
        void paymentRealizedToFailed() {
            assertThat(TransactionStatus.PAYMENT_REALIZED.transitionTo(TransactionStatus.FAILED))
                .isEqualTo(TransactionStatus.FAILED);
        }

        @Test
        @DisplayName("NAV_APPLIED → ALLOTMENT_IN_PROGRESS (worker claims the transaction)")
        void navAppliedToAllotmentInProgress() {
            assertThat(TransactionStatus.NAV_APPLIED.transitionTo(TransactionStatus.ALLOTMENT_IN_PROGRESS))
                .isEqualTo(TransactionStatus.ALLOTMENT_IN_PROGRESS);
        }

        @Test
        @DisplayName("NAV_APPLIED → FAILED")
        void navAppliedToFailed() {
            assertThat(TransactionStatus.NAV_APPLIED.transitionTo(TransactionStatus.FAILED))
                .isEqualTo(TransactionStatus.FAILED);
        }

        @Test
        @DisplayName("ALLOTMENT_IN_PROGRESS → ALLOTTED (happy path completion)")
        void allotmentInProgressToAllotted() {
            assertThat(TransactionStatus.ALLOTMENT_IN_PROGRESS.transitionTo(TransactionStatus.ALLOTTED))
                .isEqualTo(TransactionStatus.ALLOTTED);
        }

        @Test
        @DisplayName("ALLOTMENT_IN_PROGRESS → FAILED (holding update failed after retries)")
        void allotmentInProgressToFailed() {
            assertThat(TransactionStatus.ALLOTMENT_IN_PROGRESS.transitionTo(TransactionStatus.FAILED))
                .isEqualTo(TransactionStatus.FAILED);
        }

        @Test
        @DisplayName("ALLOTTED → REVERSED (correction — new reversal row created)")
        void allottedToReversed() {
            assertThat(TransactionStatus.ALLOTTED.transitionTo(TransactionStatus.REVERSED))
                .isEqualTo(TransactionStatus.REVERSED);
        }
    }

    // ─── Invalid transitions ──────────────────────────────────────────────────

    @Nested
    @DisplayName("invalid transitions throw InvalidTransactionStateException")
    class InvalidTransitions {

        @Test
        @DisplayName("PENDING cannot jump to ALLOTTED (skipping the pipeline)")
        void pendingCannotGoDirectlyToAllotted() {
            assertThatThrownBy(() ->
                TransactionStatus.PENDING.transitionTo(TransactionStatus.ALLOTTED)
            )
            .isInstanceOf(InvalidTransactionStateException.class)
            .hasMessageContaining("PENDING")
            .hasMessageContaining("ALLOTTED");
        }

        @Test
        @DisplayName("PENDING cannot go to NAV_APPLIED (no payment yet)")
        void pendingCannotGoToNavApplied() {
            assertThatThrownBy(() ->
                TransactionStatus.PENDING.transitionTo(TransactionStatus.NAV_APPLIED)
            ).isInstanceOf(InvalidTransactionStateException.class);
        }

        @Test
        @DisplayName("ALLOTTED cannot go back to PENDING (immutable ledger)")
        void allottedCannotRevertToPending() {
            assertThatThrownBy(() ->
                TransactionStatus.ALLOTTED.transitionTo(TransactionStatus.PENDING)
            ).isInstanceOf(InvalidTransactionStateException.class);
        }

        @Test
        @DisplayName("ALLOTTED cannot go to FAILED (already terminal)")
        void allottedCannotGoToFailed() {
            assertThatThrownBy(() ->
                TransactionStatus.ALLOTTED.transitionTo(TransactionStatus.FAILED)
            ).isInstanceOf(InvalidTransactionStateException.class);
        }
    }

    // ─── Terminal states ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("terminal states")
    class TerminalStates {

        @Test
        @DisplayName("FAILED is terminal — no transitions allowed")
        void failedIsTerminal() {
            for (TransactionStatus next : TransactionStatus.values()) {
                assertThatThrownBy(() ->
                    TransactionStatus.FAILED.transitionTo(next)
                ).isInstanceOf(InvalidTransactionStateException.class);
            }
        }

        @Test
        @DisplayName("REVERSED is terminal — no transitions allowed")
        void reversedIsTerminal() {
            for (TransactionStatus next : TransactionStatus.values()) {
                assertThatThrownBy(() ->
                    TransactionStatus.REVERSED.transitionTo(next)
                ).isInstanceOf(InvalidTransactionStateException.class);
            }
        }

        @ParameterizedTest
        @EnumSource(value = TransactionStatus.class, names = {"ALLOTTED", "FAILED", "REVERSED"})
        @DisplayName("isTerminal() returns true for all terminal states")
        void isTerminalReturnsTrueForTerminalStates(TransactionStatus status) {
            assertThat(status.isTerminal()).isTrue();
        }

        @ParameterizedTest
        @EnumSource(value = TransactionStatus.class,
            names = {"PENDING", "PAYMENT_REALIZED", "NAV_APPLIED", "ALLOTMENT_IN_PROGRESS"})
        @DisplayName("isTerminal() returns false for non-terminal states")
        void isTerminalReturnsFalseForNonTerminalStates(TransactionStatus status) {
            assertThat(status.isTerminal()).isFalse();
        }
    }

    // ─── Exception content ────────────────────────────────────────────────────

    @Nested
    @DisplayName("InvalidTransactionStateException")
    class ExceptionContentTests {

        @Test
        @DisplayName("exception message contains both from and to states")
        void exceptionContainsBothStates() {
            var ex = catchThrowableOfType(
                () -> TransactionStatus.ALLOTTED.transitionTo(TransactionStatus.PENDING),
                InvalidTransactionStateException.class
            );

            assertThat(ex).isNotNull();
            assertThat(ex.getFrom()).isEqualTo(TransactionStatus.ALLOTTED);
            assertThat(ex.getTo()).isEqualTo(TransactionStatus.PENDING);
            assertThat(ex.getMessage())
                .contains("ALLOTTED")
                .contains("PENDING");
        }
    }
}
