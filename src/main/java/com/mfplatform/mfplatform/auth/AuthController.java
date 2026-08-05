package com.mfplatform.mfplatform.auth;

import com.mfplatform.mfplatform.auth.dto.AuthDtos.*;
import com.mfplatform.mfplatform.distributor.dto.DistributorDtos.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Authentication", description = "Login, token refresh, and public self-signup endpoints. No JWT required.")

/**
 * AuthController — public endpoints requiring no authentication.
 *
 * POST /auth/login               — login for any user type
 * POST /auth/refresh             — refresh tokens
 * POST /auth/signup/investor     — public investor self-signup
 * POST /auth/signup/distributor  — public distributor self-signup
 *                                  (creates PENDING_VERIFICATION, background worker activates)
 *
 * All endpoints are permitted without auth in SecurityConfig
 * via .requestMatchers("/api/v1/auth/**").permitAll()
 *
 * NOTE ON DISTRIBUTOR SIGNUP:
 * There is intentionally no /auth/signup/distributor endpoint that creates
 * an ACTIVE distributor directly. Distributors self-signup as
 * PENDING_VERIFICATION — a background worker (DistributorVerificationWorker)
 * activates them after simulated ARN verification (~60 seconds in demo mode).
 * Admins can also onboard distributors directly as ACTIVE via POST /distributors.
 * See ADR-008 for the full reasoning.
 *
 * ALL DTOs used by this controller live in AuthDtos.java and DistributorDtos.java.
 * No DTO definitions belong in this file — controllers are HTTP wiring only.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Operation(
        summary = "Login",
        description = "Authenticates any user type (INVESTOR, DISTRIBUTOR, ADMIN). " +
            "Returns access token (15 min) and refresh token (7 days). " +
            "Default admin: username=admin, password=ChangeMe123!"
    )
    @ApiResponse(responseCode = "200", description = "Login successful — tokens returned")
    @ApiResponse(responseCode = "401", description = "Invalid username or password")
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @Operation(summary = "Refresh tokens", description = "Exchange a valid refresh token for a new access + refresh token pair.")
    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(authService.refresh(request));
    }

    /**
     * Public investor self-signup.
     * All fields come from InvestorSignupRequest in AuthDtos.java.
     * Logs the investor in immediately after creation and returns JWT tokens.
     */
    @Operation(
        summary = "Investor self-signup",
        description = "Creates an investor account and logs them in immediately. Returns JWT tokens."
    )
    @PostMapping("/signup/investor")
    public ResponseEntity<LoginResponse> signupInvestor(
            @Valid @RequestBody InvestorSignupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(authService.signupInvestor(request));
    }

    /**
     * Public distributor self-signup.
     *
     * Creates the distributor with PENDING_VERIFICATION status.
     * Returns tokens immediately so the distributor can log in and view
     * their own profile — but restricted operations (adding investors, placing
     * transactions) are blocked by @RequireActiveDistributor until the
     * background worker activates the account (~60 seconds in demo mode).
     *
     * Response includes:
     *   - accessToken / refreshToken  (for immediate login)
     *   - status: "PENDING_VERIFICATION"
     *   - message: human-readable explanation of what's happening
     */
    @Operation(
        summary = "Distributor self-signup",
        description = "Creates a distributor account with PENDING_VERIFICATION status. " +
            "ARN is verified by a background worker within ~60 seconds. " +
            "Tokens are returned immediately — restricted endpoints unlock after verification."
    )
    @PostMapping("/signup/distributor")
    public ResponseEntity<DistributorSignupResponse> signupDistributor(
            @Valid @RequestBody DistributorSignupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(authService.signupDistributor(request));
    }
}
