package com.mfplatform.mfplatform.investor;

public class InvestorNotFoundException extends RuntimeException {
    public InvestorNotFoundException(Long id) {
        super("Investor not found: " + id);
    }
}
