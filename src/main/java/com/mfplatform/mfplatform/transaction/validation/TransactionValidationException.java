package com.mfplatform.mfplatform.transaction.validation;

/**
 * Thrown by any TransactionValidator to stop the validation chain and
 * reject the transaction.
 *
 * The message should be human-readable and safe to return to the caller —
 * it will be included in the 422 response body via GlobalExceptionHandler.
 *
 * Examples:
 *   "KYC not complete for investor ID 42"
 *   "Minimum purchase amount for this scheme is ₹1000"
 *   "Insufficient units: requested 200 but only 150.5 held"
 *   "This scheme is currently closed for purchase"
 */
public class TransactionValidationException extends RuntimeException {

    public TransactionValidationException(String message) {
        super(message);
    }
}
