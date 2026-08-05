package com.mfplatform.mfplatform.investor;

import com.mfplatform.mfplatform.BaseIntegrationTest;
import com.mfplatform.mfplatform.distributor.Distributor;
import com.mfplatform.mfplatform.distributor.DistributorRepository;
import com.mfplatform.mfplatform.distributor.DistributorStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration test for InvestorRepository.
 *
 * Tests real SQL queries against a real PostgreSQL 16 database
 * (started by Testcontainers, migrated by Flyway).
 *
 * WHAT WE'RE TESTING:
 *   1. findByDistributorId() — the query that powers role-scoped investor listing
 *   2. Pagination works correctly (Page<Investor>)
 *   3. findByEmail() — used in duplicate detection
 *   4. findByPanNumber() — used in duplicate detection
 *   5. That the UNIQUE constraint on email/pan is actually enforced by the DB
 *
 * @Autowired works because BaseIntegrationTest loads the full Spring context
 * with @SpringBootTest — real beans, real repositories, real Flyway migrations.
 *
 * NOTE ON TEST ISOLATION:
 * Each test class shares the same container (for speed), but we clean up
 * in @BeforeEach to ensure tests don't interfere with each other.
 * Spring's @Transactional on test methods (not used here) would auto-rollback
 * after each test — we use deleteAll() instead for explicitness.
 */
@DisplayName("InvestorRepository (Integration)")
@Disabled("Testcontainers can't reach a Docker daemon in the current environment " +
        "(Windows Docker Desktop socket isn't reachable from a nested container the way " +
        "this suite is currently being run) - Previous attempts to find a Docker environment failed. " +
        "Re-enable once run directly on a host with working Testcontainers/Docker access.")
class InvestorRepositoryIntegrationTest extends BaseIntegrationTest {

    @Autowired private InvestorRepository   investorRepository;
    @Autowired private DistributorRepository distributorRepository;

    private Distributor distributor1;
    private Distributor distributor2;

    @BeforeEach
    void setUp() {
        // Clean up before each test — order matters due to FK constraints
        investorRepository.deleteAll();
        distributorRepository.deleteAll();

        distributor1 = distributorRepository.save(Distributor.builder()
                .name("Ramesh Kumar").arnCode("ARN-001").email("ramesh@dist.com")
                .status(DistributorStatus.ACTIVE).build());

        distributor2 = distributorRepository.save(Distributor.builder()
                .name("Sunita Rao").arnCode("ARN-002").email("sunita@dist.com")
                .status(DistributorStatus.ACTIVE).build());
    }

    // ─── findByDistributorId ──────────────────────────────────────────────────

    @Nested
    @DisplayName("findByDistributorId")
    class FindByDistributorIdTests {

        @Test
        @DisplayName("returns only investors belonging to the specified distributor")
        void returnsOnlyMatchingDistributorsInvestors() {
            // Create 2 investors under distributor1, 1 under distributor2
            investorRepository.save(Investor.builder()
                    .name("Priya Sharma").email("priya@example.com")
                    .panNumber("ABCPS1234F").distributorId(distributor1.getId()).build());
            investorRepository.save(Investor.builder()
                    .name("Arjun Patel").email("arjun@example.com")
                    .panNumber("ABCAP5678G").distributorId(distributor1.getId()).build());
            investorRepository.save(Investor.builder()
                    .name("Meera Shah").email("meera@example.com")
                    .panNumber("ABCMS9012H").distributorId(distributor2.getId()).build());

            Page<Investor> result = investorRepository.findByDistributorId(
                    distributor1.getId(), PageRequest.of(0, 10));

            assertThat(result.getTotalElements()).isEqualTo(2);
            assertThat(result.getContent())
                    .extracting(Investor::getEmail)
                    .containsExactlyInAnyOrder("priya@example.com", "arjun@example.com");
        }

        @Test
        @DisplayName("returns empty page when distributor has no investors")
        void returnsEmptyPageForNewDistributor() {
            Page<Investor> result = investorRepository.findByDistributorId(
                    distributor2.getId(), PageRequest.of(0, 10));

            assertThat(result.getTotalElements()).isEqualTo(0);
            assertThat(result.getContent()).isEmpty();
        }

        @Test
        @DisplayName("does not return direct investors (null distributorId)")
        void doesNotReturnDirectInvestors() {
            investorRepository.save(Investor.builder()
                    .name("Direct Investor").email("direct@example.com")
                    .panNumber("ABCDI1111A").distributorId(null).build());

            Page<Investor> result = investorRepository.findByDistributorId(
                    distributor1.getId(), PageRequest.of(0, 10));

            assertThat(result.getTotalElements()).isEqualTo(0);
        }

        @Test
        @DisplayName("pagination works — returns correct page of results")
        void paginationWorks() {
            // Create 5 investors under distributor1
            for (int i = 1; i <= 5; i++) {
                investorRepository.save(Investor.builder()
                        .name("Investor " + i).email("investor" + i + "@example.com")
                        .panNumber("ABCIN" + String.format("%04d", i) + "A")
                        .distributorId(distributor1.getId()).build());
            }

            // Request page 0, size 3 → should get 3 investors
            Page<Investor> page0 = investorRepository.findByDistributorId(
                    distributor1.getId(), PageRequest.of(0, 3));
            // Request page 1, size 3 → should get 2 remaining
            Page<Investor> page1 = investorRepository.findByDistributorId(
                    distributor1.getId(), PageRequest.of(1, 3));

            assertThat(page0.getTotalElements()).isEqualTo(5);
            assertThat(page0.getContent()).hasSize(3);
            assertThat(page1.getContent()).hasSize(2);
            assertThat(page0.getTotalPages()).isEqualTo(2);
        }
    }

    // ─── Unique constraints ───────────────────────────────────────────────────

    @Nested
    @DisplayName("unique constraints (DB-enforced)")
    class UniqueConstraintTests {

        @Test
        @DisplayName("email uniqueness is enforced at the DB level")
        void emailMustBeUnique() {
            investorRepository.save(Investor.builder()
                    .name("First").email("duplicate@example.com")
                    .panNumber("ABCFI1111A").build());

            assertThatThrownBy(() ->
                investorRepository.saveAndFlush(Investor.builder()
                        .name("Second").email("duplicate@example.com")
                        .panNumber("ABCSE2222B").build())
            ).isInstanceOf(Exception.class); // DataIntegrityViolationException at runtime
        }

        @Test
        @DisplayName("PAN number uniqueness is enforced at the DB level")
        void panMustBeUnique() {
            investorRepository.save(Investor.builder()
                    .name("First").email("first@example.com")
                    .panNumber("ABCFI1111A").build());

            assertThatThrownBy(() ->
                investorRepository.saveAndFlush(Investor.builder()
                        .name("Second").email("second@example.com")
                        .panNumber("ABCFI1111A").build()) // same PAN
            ).isInstanceOf(Exception.class);
        }
    }

    // ─── findByEmail / findByPanNumber ────────────────────────────────────────

    @Nested
    @DisplayName("lookup by email / PAN")
    class LookupTests {

        @Test
        @DisplayName("findByEmail returns the investor when email exists")
        void findByEmailReturnsInvestor() {
            investorRepository.save(Investor.builder()
                    .name("Priya Sharma").email("priya@example.com")
                    .panNumber("ABCPS1234F").build());

            var result = investorRepository.findByEmail("priya@example.com");

            assertThat(result).isPresent();
            assertThat(result.get().getName()).isEqualTo("Priya Sharma");
        }

        @Test
        @DisplayName("findByEmail returns empty when email does not exist")
        void findByEmailReturnsEmptyForUnknownEmail() {
            var result = investorRepository.findByEmail("nobody@example.com");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("findByPanNumber returns the investor when PAN exists")
        void findByPanNumberReturnsInvestor() {
            investorRepository.save(Investor.builder()
                    .name("Rahul Mehta").email("rahul@example.com")
                    .panNumber("ABCRM5678G").build());

            var result = investorRepository.findByPanNumber("ABCRM5678G");

            assertThat(result).isPresent();
            assertThat(result.get().getEmail()).isEqualTo("rahul@example.com");
        }
    }
}
