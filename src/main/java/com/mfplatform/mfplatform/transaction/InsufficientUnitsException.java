package com.mfplatform.mfplatform.transaction;

/**
 * Thrown by Holding.subtractUnits() when a redemption would result in a
 * negative unit balance.
 *
 * This should normally be caught by SufficientUnitsValidator in the
 * TransactionValidationChain BEFORE a transaction is even created.
 * Holding.subtractUnits() throwing this is a last-resort safety net —
 * it means the validator was bypassed or a concurrent redemption reduced
 * the balance between validation and allotment.
 *
 * Caught by GlobalExceptionHandler and returned as 422 Unprocessable Entity.
 */
public class InsufficientUnitsException extends RuntimeException {
    public InsufficientUnitsException(String message) {
        super(message);
    }
}
