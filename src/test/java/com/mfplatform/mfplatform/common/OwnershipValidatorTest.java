package com.mfplatform.mfplatform.common;

import com.mfplatform.mfplatform.folio.Folio;
import com.mfplatform.mfplatform.folio.FolioRepository;
import com.mfplatform.mfplatform.investor.Investor;
import com.mfplatform.mfplatform.investor.InvestorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for OwnershipValidator.
 *
 * This is the most important security component to test — it's the single
 * source of truth for "does actor X have access to entity Y."
 * Any bug here leaks one investor's data to another.
 *
 * MOCKITO PATTERN:
 * @ExtendWith(MockitoExtension.class) — activates Mockito without Spring context
 * @Mock — creates a mock of the annotated type
 * @InjectMocks — creates OwnershipValidator and injects the mocks into it
 *
 * when(mock.method(arg)).thenReturn(value) — stubs the mock to return a
 * specific value when called with specific arguments.
 *
 * verify(mock).method(arg) — asserts the mock was called exactly once
 * with those arguments. Useful for confirming DB queries actually ran.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OwnershipValidator")
class OwnershipValidatorTest {

    @Mock
    private InvestorRepository investorRepository;

    @Mock
    private FolioRepository folioRepository;

    @InjectMocks
    private OwnershipValidator ownershipValidator;

    // Test data — built fresh before each test
    private Investor investorWithDistributor;
    private Investor directInvestor;
    private Folio    folioOwnedByInvestor7;

    private static final Long INVESTOR_ID    = 7L;
    private static final Long DISTRIBUTOR_ID = 3L;
    private static final Long OTHER_DIST_ID  = 99L;
    private static final Long FOLIO_ID       = 42L;

    @BeforeEach
    void setUp() {
        investorWithDistributor = Investor.builder()
                .id(INVESTOR_ID)
                .name("Priya Sharma")
                .email("priya@example.com")
                .panNumber("ABCPS1234F")
                .distributorId(DISTRIBUTOR_ID)
                .build();

        directInvestor = Investor.builder()
                .id(8L)
                .name("Rahul Mehta")
                .email("rahul@example.com")
                .panNumber("ABCRM5678G")
                .distributorId(null)  // direct — no distributor
                .build();

        folioOwnedByInvestor7 = Folio.builder()
                .id(FOLIO_ID)
                .folioNumber("FL3A2B1C9D")
                .investorId(INVESTOR_ID)
                .build();
    }

    // ─── isDistributorClient ─────────────────────────────────────────────────

    @Nested
    @DisplayName("isDistributorClient")
    class IsDistributorClientTests {

        @Test
        @DisplayName("returns true when investor belongs to this distributor")
        void returnsTrueForOwnClient() {
            when(investorRepository.findById(INVESTOR_ID))
                    .thenReturn(Optional.of(investorWithDistributor));

            boolean result = ownershipValidator.isDistributorClient(INVESTOR_ID, DISTRIBUTOR_ID);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("returns false when investor belongs to a different distributor")
        void returnsFalseForOtherDistributorsClient() {
            when(investorRepository.findById(INVESTOR_ID))
                    .thenReturn(Optional.of(investorWithDistributor));

            // OTHER_DIST_ID (99) ≠ DISTRIBUTOR_ID (3) — not their client
            boolean result = ownershipValidator.isDistributorClient(INVESTOR_ID, OTHER_DIST_ID);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("returns false when investor is direct (no distributor)")
        void returnsFalseForDirectInvestor() {
            when(investorRepository.findById(8L))
                    .thenReturn(Optional.of(directInvestor));

            boolean result = ownershipValidator.isDistributorClient(8L, DISTRIBUTOR_ID);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("returns false (not throws) when investor does not exist")
        void returnsFalseWhenInvestorNotFound() {
            when(investorRepository.findById(999L))
                    .thenReturn(Optional.empty());

            // orElse(false) — unknown entity = no access. Safe default.
            boolean result = ownershipValidator.isDistributorClient(999L, DISTRIBUTOR_ID);

            assertThat(result).isFalse();
        }
    }

    // ─── assertIsDistributorClient ────────────────────────────────────────────

    @Nested
    @DisplayName("assertIsDistributorClient")
    class AssertIsDistributorClientTests {

        @Test
        @DisplayName("does not throw when investor is the distributor's client")
        void doesNotThrowForOwnClient() {
            when(investorRepository.findById(INVESTOR_ID))
                    .thenReturn(Optional.of(investorWithDistributor));

            assertThatNoException().isThrownBy(() ->
                ownershipValidator.assertIsDistributorClient(INVESTOR_ID, DISTRIBUTOR_ID)
            );
        }

        @Test
        @DisplayName("throws AccessDeniedException when investor is not their client")
        void throwsForNotOwnClient() {
            when(investorRepository.findById(INVESTOR_ID))
                    .thenReturn(Optional.of(investorWithDistributor));

            assertThatThrownBy(() ->
                ownershipValidator.assertIsDistributorClient(INVESTOR_ID, OTHER_DIST_ID)
            )
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("client book");
        }
    }

    // ─── isInvestorFolio ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("isInvestorFolio")
    class IsInvestorFolioTests {

        @Test
        @DisplayName("returns true when folio belongs to this investor")
        void returnsTrueForOwnFolio() {
            when(folioRepository.findById(FOLIO_ID))
                    .thenReturn(Optional.of(folioOwnedByInvestor7));

            boolean result = ownershipValidator.isInvestorFolio(FOLIO_ID, INVESTOR_ID);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("returns false when folio belongs to a different investor")
        void returnsFalseForOtherInvestorsFolio() {
            when(folioRepository.findById(FOLIO_ID))
                    .thenReturn(Optional.of(folioOwnedByInvestor7));

            // Investor 99 doesn't own folio 42 (investor 7 does)
            boolean result = ownershipValidator.isInvestorFolio(FOLIO_ID, 99L);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("returns false when folio does not exist")
        void returnsFalseWhenFolioNotFound() {
            when(folioRepository.findById(999L))
                    .thenReturn(Optional.empty());

            boolean result = ownershipValidator.isInvestorFolio(999L, INVESTOR_ID);

            assertThat(result).isFalse();
        }
    }

    // ─── isDistributorFolio ───────────────────────────────────────────────────

    @Nested
    @DisplayName("isDistributorFolio")
    class IsDistributorFolioTests {

        @Test
        @DisplayName("returns true when folio's investor is in this distributor's book")
        void returnsTrueWhenFoliosBelongsToDistributorsClient() {
            // Folio → investorId=7 → investor 7 has distributorId=3
            when(folioRepository.findById(FOLIO_ID))
                    .thenReturn(Optional.of(folioOwnedByInvestor7));
            when(investorRepository.findById(INVESTOR_ID))
                    .thenReturn(Optional.of(investorWithDistributor));

            boolean result = ownershipValidator.isDistributorFolio(FOLIO_ID, DISTRIBUTOR_ID);

            assertThat(result).isTrue();

            // Verify the two-hop lookup actually happened
            verify(folioRepository).findById(FOLIO_ID);
            verify(investorRepository).findById(INVESTOR_ID);
        }

        @Test
        @DisplayName("returns false when folio's investor belongs to a different distributor")
        void returnsFalseWhenFolioBelongsToDifferentDistributor() {
            when(folioRepository.findById(FOLIO_ID))
                    .thenReturn(Optional.of(folioOwnedByInvestor7));
            when(investorRepository.findById(INVESTOR_ID))
                    .thenReturn(Optional.of(investorWithDistributor));

            boolean result = ownershipValidator.isDistributorFolio(FOLIO_ID, OTHER_DIST_ID);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("returns false when folio belongs to a direct investor (no distributor)")
        void returnsFalseForDirectInvestorsFolio() {
            Folio directFolio = Folio.builder()
                    .id(55L).folioNumber("FL99999").investorId(8L).build();

            when(folioRepository.findById(55L))
                    .thenReturn(Optional.of(directFolio));
            when(investorRepository.findById(8L))
                    .thenReturn(Optional.of(directInvestor)); // distributorId = null

            boolean result = ownershipValidator.isDistributorFolio(55L, DISTRIBUTOR_ID);

            assertThat(result).isFalse();
        }
    }
}
