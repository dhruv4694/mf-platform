package com.mfplatform.mfplatform.distributor;

/**
 * Thrown by DistributorService when a distributor in PENDING_VERIFICATION,
 * REJECTED, or SUSPENDED status attempts an operation that requires ACTIVE status.
 *
 * This is the Option A enforcement mechanism — login works for all distributors,
 * but meaningful operations are blocked here at the service layer.
 *
 * Caught by GlobalExceptionHandler and returned as 403 Forbidden with a
 * message explaining the distributor's current status.
 */
public class DistributorNotActiveException extends RuntimeException {

    public DistributorNotActiveException(Long distributorId, DistributorStatus status) {
        super(buildMessage(distributorId, status));
    }

    private static String buildMessage(Long id, DistributorStatus status) {
        return switch (status) {
            case PENDING_VERIFICATION ->
                "Distributor account " + id + " is pending ARN verification. " +
                "You will be notified once verification is complete (typically within 1 minute for demo).";
            case REJECTED ->
                "Distributor account " + id + " was rejected during ARN verification. " +
                "Please contact the fund house for assistance.";
            case SUSPENDED ->
                "Distributor account " + id + " has been suspended. " +
                "Please contact the fund house for assistance.";
            case ACTIVE ->
                // Should never happen — active distributors don't trigger this exception
                "Unexpected state for distributor " + id;
        };
    }
}
