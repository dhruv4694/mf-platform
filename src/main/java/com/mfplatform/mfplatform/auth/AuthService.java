package com.mfplatform.mfplatform.auth;

import com.mfplatform.mfplatform.aspect.Auditable;
import com.mfplatform.mfplatform.auth.dto.AuthDtos.*;
import com.mfplatform.mfplatform.notification.event.ApplicationEvents.*;
import com.mfplatform.mfplatform.distributor.Distributor;
import com.mfplatform.mfplatform.distributor.DistributorService;
import com.mfplatform.mfplatform.distributor.DistributorStatus;
import com.mfplatform.mfplatform.distributor.dto.DistributorDtos.*;
import com.mfplatform.mfplatform.investor.Investor;
import com.mfplatform.mfplatform.investor.InvestorService;
import com.mfplatform.mfplatform.investor.dto.InvestorDtos.*;
import com.mfplatform.mfplatform.security.JwtService;
import com.mfplatform.mfplatform.security.UserAccount;
import com.mfplatform.mfplatform.security.UserAccountRepository;
import com.mfplatform.mfplatform.security.UserService;
import io.jsonwebtoken.JwtException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AuthService is the orchestrator for all flows that create or authenticate sessions.
 *
 * FLOWS:
 *   login()                — authenticate any user type, return tokens
 *   refresh()              — issue new tokens from a valid refresh token
 *   signupInvestor()       — public: create investor + user_account, return tokens
 *   signupDistributor()    — PUBLIC: create distributor (PENDING_VERIFICATION)
 *                            + user_account, return tokens + status message
 *   addInvestor()          — privileged (ADMIN/DISTRIBUTOR): create investor + account
 *   addDistributor()       — privileged (ADMIN): create distributor (ACTIVE) + account
 *
 * KEY DIFFERENCE between signupDistributor() and addDistributor():
 *   signupDistributor → PENDING_VERIFICATION (awaits background worker)
 *   addDistributor    → ACTIVE immediately (admin already verified out-of-band)
 */
@Service
public class AuthService {

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final InvestorService investorService;
    private final DistributorService distributorService;
    private final UserService userService;
    private final ApplicationEventPublisher eventPublisher;

    public AuthService(
            UserAccountRepository userAccountRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            InvestorService investorService,
            DistributorService distributorService,
            UserService userService,
            ApplicationEventPublisher eventPublisher) {
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.investorService = investorService;
        this.distributorService = distributorService;
        this.userService = userService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Authenticates a user and returns a JWT token pair.
     * Works for all roles — INVESTOR, DISTRIBUTOR, ADMIN.
     * Does NOT check distributor status (Option A decision).
     *
     * Same error message for "username not found" and "wrong password"
     * to prevent username enumeration attacks.
     */
    public LoginResponse login(LoginRequest request) {
        UserAccount account = userAccountRepository.findByUsername(request.username())
                .orElseThrow(() -> new BadCredentialsException("Invalid username or password"));

        if (!passwordEncoder.matches(request.password(), account.getPasswordHash())) {
            throw new BadCredentialsException("Invalid username or password");
        }

        return new LoginResponse(
                jwtService.generateAccessToken(account),
                jwtService.generateRefreshToken(account)
        );
    }

    /**
     * Issues a new token pair from a valid refresh token.
     */
    public LoginResponse refresh(RefreshRequest request) {
        var claims = jwtService.parseClaims(request.refreshToken());

        if (!"refresh".equals(claims.get("type", String.class))) {
            throw new JwtException("Token provided is not a refresh token");
        }

        Long userId = claims.get("userId", Long.class);
        UserAccount account = userAccountRepository.findById(userId)
                .orElseThrow(() -> new JwtException("Account no longer exists"));

        return new LoginResponse(
                jwtService.generateAccessToken(account),
                jwtService.generateRefreshToken(account)
        );
    }

    /**
     * Public investor self-signup.
     * Creates investor entity + user_account in one @Transactional boundary.
     * Logs the investor in immediately after signup.
     */
    @Auditable(operation = "INVESTOR_SIGNUP")
    @Transactional
    public LoginResponse signupInvestor(InvestorSignupRequest request) {
        Investor investor = investorService.createInvestor(
                request.name(), request.email(),
                request.panNumber(), request.distributorId());

        userService.createInvestorAccount(
                investor.getId(), request.username(), request.password());

        // Publish AFTER both rows saved — @TransactionalEventListener(AFTER_COMMIT)
        // in NotificationService fires after this transaction commits, not now.
        // This prevents the notification from firing if the transaction rolls back.
        eventPublisher.publishEvent(new InvestorSignedUpEvent(this, investor));

        return login(new LoginRequest(request.username(), request.password()));
    }

    /**
     * PUBLIC distributor self-signup.
     *
     * Creates distributor with PENDING_VERIFICATION status + user_account.
     * The distributor can log in immediately but cannot perform restricted
     * operations until the background worker activates them (within 60s for demo).
     *
     * Returns tokens AND a status message so the distributor knows they're
     * pending — not just a silent login response.
     */
    @Auditable(operation = "DISTRIBUTOR_SIGNUP")
    @Transactional
    public DistributorSignupResponse signupDistributor(DistributorSignupRequest request) {
        // Step 1: create the distributor entity (PENDING_VERIFICATION)
        Distributor distributor = distributorService.createDistributor(
                request.name(), request.arnCode(), request.email(),
                DistributorStatus.PENDING_VERIFICATION);

        // Step 2: create login credentials
        userService.createDistributorAccount(
                distributor.getId(), request.username(), request.password());

        // Step 3: notify distributor that ARN verification is in progress
        eventPublisher.publishEvent(new DistributorSignedUpEvent(this, distributor));

        // Step 4: issue tokens immediately — distributor can log in now
        LoginResponse tokens = login(
                new LoginRequest(request.username(), request.password()));

        return new DistributorSignupResponse(
                tokens.accessToken(),
                tokens.refreshToken(),
                DistributorStatus.PENDING_VERIFICATION.name(),
                "Your ARN (" + request.arnCode() + ") is being verified. " +
                "You will be able to add investors and manage your client book " +
                "once verification is complete (typically within 60 seconds in demo mode)."
        );
    }

    /**
     * Privileged investor creation — ADMIN or DISTRIBUTOR adding a client.
     * Distributor must be ACTIVE to add investors (enforced in InvestorController
     * which calls assertDistributorActive before this method).
     */
    @Transactional
    public InvestorResponse addInvestor(AddInvestorRequest request,
                                        org.springframework.security.core.Authentication auth) {
        Investor investor = investorService.addInvestor(request, auth);
        userService.createInvestorAccount(
                investor.getId(), request.username(), request.temporaryPassword());
        return investorService.toResponse(investor);
    }

    /**
     * ADMIN-only distributor creation — creates ACTIVE immediately.
     * Used when admin has already verified the ARN out-of-band.
     */
    @Transactional
    public DistributorResponse addDistributor(AddDistributorRequest request) {
        Distributor distributor = distributorService.createDistributor(
                request.name(), request.arnCode(), request.email(),
                DistributorStatus.ACTIVE);

        userService.createDistributorAccount(
                distributor.getId(), request.username(), request.temporaryPassword());

        return distributorService.toResponse(distributor);
    }
}
