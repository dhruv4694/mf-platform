package com.mfplatform.mfplatform.transaction;

/**
 * Thrown by TransactionStatus.transitionTo() when an invalid state transition
 * is attempted.
 *
 * For example, trying to move a transaction from ALLOTTED back to PENDING,
 * or from FAILED to ALLOTTED — both are programming errors, not user errors.
 *
 * Caught by GlobalExceptionHandler and returned as 500 Internal Server Error
 * since this should never happen in a correctly implemented pipeline.
 *
 * Having a specific exception type (rather than IllegalStateException) means
 * you can catch it specifically in tests and verify the right transition was
 * attempted.
 */
public class InvalidTransactionStateException extends RuntimeException {

    private final TransactionStatus from;
    private final TransactionStatus to;

    public InvalidTransactionStateException(TransactionStatus from, TransactionStatus to) {
        super("Invalid transaction state transition: " + from + " → " + to);
        this.from = from;
        this.to = to;
    }

    public TransactionStatus getFrom() { return from; }
    public TransactionStatus getTo() { return to; }
}
