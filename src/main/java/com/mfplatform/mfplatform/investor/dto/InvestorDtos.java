package com.mfplatform.mfplatform.investor.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public class InvestorDtos {

    public record AddInvestorRequest(
            @NotBlank String name,
            @Email @NotBlank String email,
            @NotBlank String panNumber,
            Long distributorId,
            @NotBlank String username,
            @NotBlank @Size(min = 8, message = "Password must be at least 8 characters") String temporaryPassword
    ) {}

    public record InvestorResponse(
            Long id,
            String name,
            String email,
            String panNumber,
            Long distributorId,
            boolean kycComplete,   // needed by frontend InvestorsPage KYC badge
            Instant createdAt
    ) {}
}
