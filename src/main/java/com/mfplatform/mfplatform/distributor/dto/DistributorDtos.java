package com.mfplatform.mfplatform.distributor.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public class DistributorDtos {

    /**
     * Public self-signup request.
     * Creates distributor with PENDING_VERIFICATION status.
     */
    public record DistributorSignupRequest(
            @NotBlank String name,
            @NotBlank String arnCode,
            @Email @NotBlank String email,
            @NotBlank String username,
            @NotBlank @Size(min = 8, message = "Password must be at least 8 characters")
            String password
    ) {}

    /**
     * Admin creation request.
     * Creates distributor with ACTIVE status immediately (ARN verified out-of-band).
     */
    public record AddDistributorRequest(
            @NotBlank String name,
            @NotBlank String arnCode,
            @Email @NotBlank String email,
            @NotBlank String username,
            @NotBlank @Size(min = 8, message = "Password must be at least 8 characters")
            String temporaryPassword
    ) {}

    /**
     * Response — includes status and verification timestamps.
     * status lets the distributor know whether they're verified yet.
     * verifiedAt is null until verification completes.
     */
    public record DistributorResponse(
            Long id,
            String name,
            String arnCode,
            String email,
            String status,        // PENDING_VERIFICATION / ACTIVE / REJECTED / SUSPENDED
            Instant submittedAt,
            Instant verifiedAt,   // null until verified
            Instant createdAt
    ) {}

    /**
     * Login response after signup — same as auth login response.
     * Distributor can log in immediately even while PENDING_VERIFICATION.
     */
    public record DistributorSignupResponse(
            String accessToken,
            String refreshToken,
            String status,        // so distributor knows they're pending
            String message        // e.g. "ARN verification in progress. You'll be notified when active."
    ) {}
}
