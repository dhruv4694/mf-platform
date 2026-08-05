package com.mfplatform.mfplatform.transaction;

public class TransactionNotFoundException extends RuntimeException {
    public TransactionNotFoundException(Long id) {
        super("Transaction not found: " + id);
    }
}
