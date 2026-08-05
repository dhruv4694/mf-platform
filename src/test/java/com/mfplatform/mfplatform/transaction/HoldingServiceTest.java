package com.mfplatform.mfplatform.transaction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for HoldingService.
 *
 * ARCHITECTURE NOTE:
 * HoldingService only has two public methods:
 *   - getOrCreate(folioId, schemeId)    → finds or creates a Holding row
 *   - getCurrentUnits(folioId, schemeId) → reads current units (ZERO if no holding)
 *
 * addUnits() and subtractUnits() are domain methods on the Holding ENTITY,
 * not on HoldingService. UnitAllotmentService calls:
 *   holding = holdingService.getOrCreate(folioId, schemeId)
 *   holding.addUnits(amount)       // on the entity
 *   holdingRepository.save(holding) // persist
 *
 * So these tests focus on what HoldingService actually does:
 *   1. getOrCreate — returns existing or creates new with ZERO units
 *   2. getOrCreate concurrency — handles DataIntegrityViolationException
 *   3. getCurrentUnits — returns units or ZERO if no holding
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HoldingService")
class HoldingServiceTest {

    @Mock
    private HoldingRepository holdingRepository;

    @InjectMocks
    private HoldingService holdingService;

    private static final Long FOLIO_ID  = 42L;
    private static final Long SCHEME_ID = 1L;

    private Holding existingHolding;

    @BeforeEach
    void setUp() {
        existingHolding = Holding.builder()
                .id(10L)
                .folioId(FOLIO_ID)
                .schemeId(SCHEME_ID)
                .unitsHeld(new BigDecimal("103.6765"))
                .build();
    }

    // ─── getOrCreate ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getOrCreate")
    class GetOrCreateTests {

        @Test
        @DisplayName("returns existing holding when found")
        void returnsExistingHolding() {
            when(holdingRepository.findByFolioIdAndSchemeId(FOLIO_ID, SCHEME_ID))
                    .thenReturn(Optional.of(existingHolding));

            Holding result = holdingService.getOrCreate(FOLIO_ID, SCHEME_ID);

            assertThat(result).isSameAs(existingHolding);
            assertThat(result.getUnitsHeld())
                    .isEqualByComparingTo(new BigDecimal("103.6765"));

            // Must not create a new holding when one already exists
            verify(holdingRepository, never()).save(any());
        }

        @Test
        @DisplayName("creates and returns a new holding with zero units when not found")
        void createsNewHoldingWhenNotFound() {
            when(holdingRepository.findByFolioIdAndSchemeId(FOLIO_ID, SCHEME_ID))
                    .thenReturn(Optional.empty());

            Holding savedHolding = Holding.builder()
                    .id(11L).folioId(FOLIO_ID).schemeId(SCHEME_ID)
                    .unitsHeld(BigDecimal.ZERO).build();
            when(holdingRepository.save(any())).thenReturn(savedHolding);

            Holding result = holdingService.getOrCreate(FOLIO_ID, SCHEME_ID);

            // Verify the new holding has correct fields
            ArgumentCaptor<Holding> captor = ArgumentCaptor.forClass(Holding.class);
            verify(holdingRepository).save(captor.capture());

            Holding toSave = captor.getValue();
            assertThat(toSave.getFolioId()).isEqualTo(FOLIO_ID);
            assertThat(toSave.getSchemeId()).isEqualTo(SCHEME_ID);
            assertThat(toSave.getUnitsHeld()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("handles concurrent insert — retries find on DataIntegrityViolationException")
        void handlesConcurrentInsertConflict() {
            // First find: nothing
            // Then save: fails (another thread inserted first)
            // Then find again: returns what the other thread created
            when(holdingRepository.findByFolioIdAndSchemeId(FOLIO_ID, SCHEME_ID))
                    .thenReturn(Optional.empty())   // first call: not found
                    .thenReturn(Optional.of(existingHolding)); // second call: found (other thread created it)

            when(holdingRepository.save(any()))
                    .thenThrow(new DataIntegrityViolationException("duplicate key"));

            Holding result = holdingService.getOrCreate(FOLIO_ID, SCHEME_ID);

            // Should return what the other thread created, not fail
            assertThat(result).isSameAs(existingHolding);
            // find was called twice
            verify(holdingRepository, times(2))
                    .findByFolioIdAndSchemeId(FOLIO_ID, SCHEME_ID);
        }
    }

    // ─── getCurrentUnits ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("getCurrentUnits")
    class GetCurrentUnitsTests {

        @Test
        @DisplayName("returns units held when holding exists")
        void returnsUnitsWhenHoldingExists() {
            when(holdingRepository.findByFolioIdAndSchemeId(FOLIO_ID, SCHEME_ID))
                    .thenReturn(Optional.of(existingHolding));

            BigDecimal result = holdingService.getCurrentUnits(FOLIO_ID, SCHEME_ID);

            assertThat(result).isEqualByComparingTo(new BigDecimal("103.6765"));
        }

        @Test
        @DisplayName("returns ZERO when no holding exists yet (first purchase)")
        void returnsZeroWhenNoHolding() {
            when(holdingRepository.findByFolioIdAndSchemeId(FOLIO_ID, SCHEME_ID))
                    .thenReturn(Optional.empty());

            BigDecimal result = holdingService.getCurrentUnits(FOLIO_ID, SCHEME_ID);

            assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("is read-only — never calls save()")
        void isReadOnly() {
            when(holdingRepository.findByFolioIdAndSchemeId(FOLIO_ID, SCHEME_ID))
                    .thenReturn(Optional.empty());

            holdingService.getCurrentUnits(FOLIO_ID, SCHEME_ID);

            verify(holdingRepository, never()).save(any());
        }
    }

    // ─── Holding entity domain methods ────────────────────────────────────────
    // addUnits/subtractUnits/hasUnits are on the Holding entity, not HoldingService.
    // We test them here as unit tests on the entity directly.

    @Nested
    @DisplayName("Holding entity domain methods")
    class HoldingEntityTests {

        @Test
        @DisplayName("addUnits adds to existing balance")
        void addUnitsAddsToBalance() {
            existingHolding.addUnits(new BigDecimal("50.0000"));
            // 103.6765 + 50.0000 = 153.6765
            assertThat(existingHolding.getUnitsHeld())
                    .isEqualByComparingTo(new BigDecimal("153.6765"));
        }

        @Test
        @DisplayName("subtractUnits reduces balance")
        void subtractUnitsReducesBalance() {
            existingHolding.subtractUnits(new BigDecimal("50.0000"));
            // 103.6765 - 50.0000 = 53.6765
            assertThat(existingHolding.getUnitsHeld())
                    .isEqualByComparingTo(new BigDecimal("53.6765"));
        }

        @Test
        @DisplayName("subtractUnits allows full redemption — balance goes to zero")
        void subtractUnitsAllowsFullRedemption() {
            existingHolding.subtractUnits(new BigDecimal("103.6765"));
            assertThat(existingHolding.getUnitsHeld())
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("subtractUnits throws InsufficientUnitsException when redeeming more than held")
        void subtractUnitsThrowsWhenInsufficient() {
            assertThatThrownBy(() ->
                existingHolding.subtractUnits(new BigDecimal("200.0000"))
            )
            .isInstanceOf(InsufficientUnitsException.class)
            .hasMessageContaining("103.6765")
            .hasMessageContaining("200.0000");
        }

        @Test
        @DisplayName("hasUnits returns true when units > 0")
        void hasUnitsReturnsTrueWhenPositive() {
            assertThat(existingHolding.hasUnits()).isTrue();
        }

        @Test
        @DisplayName("hasUnits returns false when units = 0 (fully redeemed)")
        void hasUnitsReturnsFalseWhenZero() {
            Holding empty = Holding.builder()
                    .id(99L).folioId(FOLIO_ID).schemeId(SCHEME_ID)
                    .unitsHeld(BigDecimal.ZERO).build();
            assertThat(empty.hasUnits()).isFalse();
        }
    }
}
