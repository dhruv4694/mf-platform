package com.mfplatform.mfplatform.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class AuthDtos {

    public record LoginRequest(
            @NotBlank String username,
            @NotBlank String password
    ) {}

    public record RefreshRequest(
            @NotBlank String refreshToken
    ) {}

    public record LoginResponse(
            String accessToken,
            String refreshToken
    ) {}

    public record InvestorSignupRequest(
            @NotBlank String name,
            @Email @NotBlank String email,
            @NotBlank String panNumber,
            Long distributorId,
            @NotBlank String username,
            @NotBlank @Size(min = 8, message = "Password must be at least 8 characters") String password
    ) {}
}
