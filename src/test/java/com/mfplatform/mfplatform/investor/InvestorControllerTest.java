package com.mfplatform.mfplatform.investor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfplatform.mfplatform.auth.AuthService;
import com.mfplatform.mfplatform.common.Role;
import com.mfplatform.mfplatform.investor.dto.InvestorDtos.*;
import com.mfplatform.mfplatform.security.ActorContext;
import com.mfplatform.mfplatform.security.JwtAuthFilter;
import com.mfplatform.mfplatform.security.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * @WebMvcTest slice test for InvestorController.
 *
 * WHY @WebMvcTest (not @SpringBootTest):
 * @WebMvcTest loads ONLY the web layer — controllers, filters, security config.
 * No services, no repositories, no DB. This makes it:
 *   - Fast (no full Spring context)
 *   - Focused (tests HTTP + security, not business logic)
 *   - Reliable (no DB infrastructure needed)
 *
 * WHAT WE'RE TESTING:
 *   1. @PreAuthorize annotations actually work (not just decorative)
 *   2. Unauthenticated requests get 403 (no custom AuthenticationEntryPoint —
 *      Spring Security 6's default handling doesn't distinguish this from a
 *      wrong-role denial)
 *   3. Wrong role gets 403
 *   4. Correct role gets through to the service
 *   5. Response is serialized correctly
 *
 * SECURITY MOCKING:
 * We use SecurityMockMvcRequestPostProcessors.authentication() to inject
 * a fake Authentication into the SecurityContext for each test.
 * This simulates what JwtAuthFilter does in production, without needing
 * a real JWT token.
 *
 * @MockBean creates a Mockito mock that IS registered in the Spring context
 * (unlike @Mock which is only in the test class).
 */
@WebMvcTest(InvestorController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class})
@DisplayName("InvestorController")
class InvestorControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean private InvestorService investorService;
    @MockBean private AuthService     authService;
    // These beans are needed by security components
    @MockBean private com.mfplatform.mfplatform.security.JwtService jwtService;
    @MockBean private com.mfplatform.mfplatform.distributor.DistributorService distributorService;
    @MockBean private com.mfplatform.mfplatform.common.OwnershipValidator ownershipValidator;

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Creates a Spring Security Authentication object with our custom ActorContext principal.
     * This is equivalent to what JwtAuthFilter does for a real request.
     */
    private static UsernamePasswordAuthenticationToken authAs(
            Long userId, Role role, Long investorId, Long distributorId) {

        ActorContext actor = new ActorContext(userId, role, investorId, distributorId);
        return new UsernamePasswordAuthenticationToken(
                actor, null,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                        "ROLE_" + role.name()))
        );
    }

    private static InvestorResponse sampleInvestorResponse() {
        return new InvestorResponse(7L, "Priya Sharma", "priya@example.com",
                "ABCPS1234F", null, false, Instant.now());
    }

    // ─── GET /investors ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /investors")
    class GetInvestorsTests {

        @Test
        @DisplayName("returns 403 when no authentication provided")
        void returns403WhenUnauthenticated() throws Exception {
            // No custom AuthenticationEntryPoint is configured, so Spring
            // Security 6's default access-denied handling applies uniformly
            // to both "not authenticated" and "authenticated but forbidden" —
            // both surface as 403, not 401.
            mockMvc.perform(get("/api/v1/investors"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("returns 403 when INVESTOR role tries to list all investors")
        void returns403ForInvestorRole() throws Exception {
            mockMvc.perform(get("/api/v1/investors")
                    .with(SecurityMockMvcRequestPostProcessors.authentication(
                            authAs(10L, Role.INVESTOR, 7L, null))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("returns 200 and investor list for ADMIN role")
        void returns200ForAdminRole() throws Exception {
            when(investorService.getInvestors(any(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(sampleInvestorResponse())));

            mockMvc.perform(get("/api/v1/investors")
                    .with(SecurityMockMvcRequestPostProcessors.authentication(
                            authAs(1L, Role.ADMIN, null, null))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.content[0].name").value("Priya Sharma"));
        }

        @Test
        @DisplayName("returns 200 for DISTRIBUTOR role (service handles scoping)")
        void returns200ForDistributorRole() throws Exception {
            when(investorService.getInvestors(any(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));
            // assertDistributorActive returns void — must use doNothing(), not when()
            doAnswer(inv -> null).when(distributorService).assertDistributorActive(any());

            mockMvc.perform(get("/api/v1/investors")
                    .with(SecurityMockMvcRequestPostProcessors.authentication(
                            authAs(2L, Role.DISTRIBUTOR, null, 3L))))
                    .andExpect(status().isOk());
        }
    }

    // ─── GET /investors/me ────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /investors/me")
    class GetMyselfTests {

        @Test
        @DisplayName("returns 200 and own investor record for INVESTOR role")
        void returns200ForInvestorRole() throws Exception {
            when(investorService.getSelf(any()))
                    .thenReturn(sampleInvestorResponse());

            mockMvc.perform(get("/api/v1/investors/me")
                    .with(SecurityMockMvcRequestPostProcessors.authentication(
                            authAs(10L, Role.INVESTOR, 7L, null))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.email").value("priya@example.com"));
        }

        @Test
        @DisplayName("returns 403 when ADMIN tries to call /me (wrong role for this endpoint)")
        void returns403ForAdminRole() throws Exception {
            mockMvc.perform(get("/api/v1/investors/me")
                    .with(SecurityMockMvcRequestPostProcessors.authentication(
                            authAs(1L, Role.ADMIN, null, null))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("returns 403 when DISTRIBUTOR tries to call /me (use /distributors/me instead)")
        void returns403ForDistributorRole() throws Exception {
            mockMvc.perform(get("/api/v1/investors/me")
                    .with(SecurityMockMvcRequestPostProcessors.authentication(
                            authAs(2L, Role.DISTRIBUTOR, null, 3L))))
                    .andExpect(status().isForbidden());
        }
    }
}
