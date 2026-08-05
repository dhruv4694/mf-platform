package com.mfplatform.mfplatform.auth;

import com.mfplatform.mfplatform.BaseIntegrationTest;
import com.mfplatform.mfplatform.auth.dto.AuthDtos.*;
import com.mfplatform.mfplatform.investor.InvestorRepository;
import com.mfplatform.mfplatform.security.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration test for AuthService.
 *
 * WHY THIS TEST MATTERS:
 * AuthService.signupInvestor() creates TWO rows in ONE @Transactional boundary:
 *   1. investor table (InvestorService)
 *   2. user_account table (UserService)
 *
 * The only way to verify this is correct is with a real database — mocks
 * can't validate that both rows are actually committed together, or that
 * the whole transaction rolls back if step 2 fails.
 *
 * WHAT WE'RE TESTING:
 *   1. Happy path: both rows created, login works immediately after signup
 *   2. Rollback: if username is duplicate, the investor row is also rolled back
 *   3. Login: correct credentials → tokens returned, wrong credentials → exception
 *   4. Password hashing: BCrypt hash stored, raw password never in DB
 *
 * @Transactional ON TEST METHODS:
 * Not used here — we want to actually see the committed state.
 * If we put @Transactional on the test method, the test would see the data
 * but it would roll back before we can assert the negative case.
 * We use @BeforeEach with deleteAll() for cleanup instead.
 */
@DisplayName("AuthService (Integration)")
@Disabled("Testcontainers can't reach a Docker daemon in the current environment " +
        "(Windows Docker Desktop socket isn't reachable from a nested container the way " +
        "this suite is currently being run) - Previous attempts to find a Docker environment failed. " +
        "Re-enable once run directly on a host with working Testcontainers/Docker access.")
class AuthServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired private AuthService          authService;
    @Autowired private InvestorRepository   investorRepository;
    @Autowired private UserAccountRepository userAccountRepository;

    @BeforeEach
    void cleanUp() {
        // FK order: user_account → investor
        userAccountRepository.deleteAll();
        investorRepository.deleteAll();
    }

    // ─── Investor signup ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("signupInvestor")
    class SignupInvestorTests {

        private final InvestorSignupRequest validRequest = new InvestorSignupRequest(
                "Priya Sharma", "priya@example.com", "ABCPS1234F",
                null, "priya.sharma", "Password123!"
        );

        @Test
        @DisplayName("creates both investor row and user_account row")
        void createsBothRows() {
            authService.signupInvestor(validRequest);

            // Both tables should have exactly one row
            assertThat(investorRepository.count()).isEqualTo(1);
            assertThat(userAccountRepository.count()).isEqualTo(2); // 1 + the test-admin from DataSeeder

            // The investor row has the right data
            var investor = investorRepository.findByEmail("priya@example.com");
            assertThat(investor).isPresent();
            assertThat(investor.get().getName()).isEqualTo("Priya Sharma");
            assertThat(investor.get().getPanNumber()).isEqualTo("ABCPS1234F");

            // The user_account row exists and is linked to the investor
            var account = userAccountRepository.findByUsername("priya.sharma");
            assertThat(account).isPresent();
            assertThat(account.get().getInvestorId()).isEqualTo(investor.get().getId());
            assertThat(account.get().getRole().name()).isEqualTo("INVESTOR");
        }

        @Test
        @DisplayName("password is BCrypt hashed — raw password not stored")
        void passwordIsHashed() {
            authService.signupInvestor(validRequest);

            var account = userAccountRepository.findByUsername("priya.sharma").orElseThrow();

            // Raw password should not appear anywhere in the hash
            assertThat(account.getPasswordHash()).doesNotContain("Password123!");
            // BCrypt hashes always start with $2a$ or $2b$
            assertThat(account.getPasswordHash()).startsWith("$2");
        }

        @Test
        @DisplayName("returns JWT tokens immediately after signup")
        void returnsTokensAfterSignup() {
            LoginResponse response = authService.signupInvestor(validRequest);

            assertThat(response).isNotNull();
            assertThat(response.accessToken()).isNotBlank();
            assertThat(response.refreshToken()).isNotBlank();
            // JWT tokens have 3 parts separated by dots
            assertThat(response.accessToken().split("\\.")).hasSize(3);
        }

        @Test
        @DisplayName("rolls back investor row if username is already taken")
        void rollsBackInvestorRowOnDuplicateUsername() {
            // First signup succeeds
            authService.signupInvestor(validRequest);

            long investorCountBefore = investorRepository.count();

            // Second signup with same USERNAME (different email/PAN)
            InvestorSignupRequest duplicate = new InvestorSignupRequest(
                    "Different Person", "different@example.com", "XYZDI9999Z",
                    null, "priya.sharma", "Password456!" // SAME username
            );

            // This should fail — and roll back the investor row too
            assertThatThrownBy(() -> authService.signupInvestor(duplicate))
                    .isInstanceOf(Exception.class);

            // Investor count should not have changed
            // (the "Different Person" investor row was rolled back)
            assertThat(investorRepository.count()).isEqualTo(investorCountBefore);

            // The "different@example.com" investor should NOT exist
            assertThat(investorRepository.findByEmail("different@example.com")).isEmpty();
        }

        @Test
        @DisplayName("rolls back investor row if PAN number is already registered")
        void rollsBackOnDuplicatePan() {
            authService.signupInvestor(validRequest);

            long investorCountBefore = investorRepository.count();

            InvestorSignupRequest duplicatePan = new InvestorSignupRequest(
                    "Second Person", "second@example.com", "ABCPS1234F", // same PAN
                    null, "second.person", "Password789!"
            );

            assertThatThrownBy(() -> authService.signupInvestor(duplicatePan))
                    .isInstanceOf(Exception.class);

            assertThat(investorRepository.count()).isEqualTo(investorCountBefore);
        }
    }

    // ─── Login ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("login")
    class LoginTests {

        @BeforeEach
        void createInvestor() {
            authService.signupInvestor(new InvestorSignupRequest(
                    "Priya Sharma", "priya@example.com", "ABCPS1234F",
                    null, "priya.sharma", "Password123!"));
        }

        @Test
        @DisplayName("returns tokens for correct credentials")
        void returnsTokensForValidCredentials() {
            LoginResponse response = authService.login(
                    new LoginRequest("priya.sharma", "Password123!"));

            assertThat(response.accessToken()).isNotBlank();
            assertThat(response.refreshToken()).isNotBlank();
        }

        @Test
        @DisplayName("throws BadCredentialsException for wrong password")
        void throwsForWrongPassword() {
            assertThatThrownBy(() ->
                authService.login(new LoginRequest("priya.sharma", "WrongPassword!"))
            )
            .isInstanceOf(org.springframework.security.authentication.BadCredentialsException.class)
            .hasMessageContaining("Invalid username or password");
        }

        @Test
        @DisplayName("throws BadCredentialsException for unknown username")
        void throwsForUnknownUsername() {
            assertThatThrownBy(() ->
                authService.login(new LoginRequest("nobody", "Password123!"))
            )
            .isInstanceOf(org.springframework.security.authentication.BadCredentialsException.class);
        }

        @Test
        @DisplayName("same error message for wrong password and unknown username (prevents enumeration)")
        void sameErrorMessagePreventsEnumeration() {
            String wrongPasswordMsg = catchThrowable(() ->
                authService.login(new LoginRequest("priya.sharma", "WrongPassword!")))
                .getMessage();

            String unknownUserMsg = catchThrowable(() ->
                authService.login(new LoginRequest("nobody", "Password123!")))
                .getMessage();

            // Both should say the same thing — attackers can't tell which failed
            assertThat(wrongPasswordMsg).isEqualTo(unknownUserMsg);
        }
    }
}
