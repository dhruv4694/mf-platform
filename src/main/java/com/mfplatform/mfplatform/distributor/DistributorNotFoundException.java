package com.mfplatform.mfplatform.distributor;

public class DistributorNotFoundException extends RuntimeException {
    public DistributorNotFoundException(Long id) {
        super("Distributor not found: " + id);
    }
}
