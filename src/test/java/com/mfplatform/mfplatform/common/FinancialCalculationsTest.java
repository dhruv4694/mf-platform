package com.mfplatform.mfplatform.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for FinancialCalculations.
 *
 * WHY THESE ARE UNIT TESTS (not integration tests):
 * FinancialCalculations contains only static pure functions — no Spring context,
 * no database, no mocks needed. Every test is just: call method → assert result.
 * These run in milliseconds and never fail due to infrastructure issues.
 *
 * WHAT WE'RE TESTING:
 *   1. Correct unit calculation (the core financial formula)
 *   2. HALF_UP rounding (AMFI standard — not HALF_EVEN or FLOOR)
 *   3. Scale (4 decimal places for units, 2 for amounts)
 *   4. Guard conditions (negative/zero inputs should throw)
 *   5. Edge cases (very small NAV, large amount)
 *
 * ASSERTJ (not JUnit assertions):
 * We use AssertJ's fluent API (assertThat(...).isEqualTo(...)) instead of
 * JUnit's assertEquals(). AssertJ gives better error messages and reads more
 * naturally. It comes with spring-boot-starter-test.
 */
@DisplayName("FinancialCalculations")
class FinancialCalculationsTest {

    // ─── calculateUnitsFromAmount ─────────────────────────────────────────────

    @Nested
    @DisplayName("calculateUnitsFromAmount")
    class CalculateUnitsFromAmountTests {

        @Test
        @DisplayName("divides amount by NAV and rounds to 4 decimal places")
        void basicDivision() {
            BigDecimal amount   = new BigDecimal("5000.00");
            BigDecimal navValue = new BigDecimal("48.2345");

            BigDecimal units = FinancialCalculations.calculateUnitsFromAmount(amount, navValue);

            // 5000 / 48.2345 = 103.660243... → HALF_UP → 103.6602
            assertThat(units).isEqualByComparingTo(new BigDecimal("103.6602"));
        }

        @Test
        @DisplayName("result has exactly 4 decimal places (AMFI standard)")
        void scaleIs4() {
            BigDecimal amount   = new BigDecimal("10000");
            BigDecimal navValue = new BigDecimal("100");

            BigDecimal units = FinancialCalculations.calculateUnitsFromAmount(amount, navValue);

            // 10000 / 100 = 100.0000 exactly
            assertThat(units.scale()).isEqualTo(4);
            assertThat(units).isEqualByComparingTo(new BigDecimal("100.0000"));
        }

        /**
         * Parameterized test: verify HALF_UP rounding for multiple amount/NAV pairs.
         * Each row is: amount, navValue, expectedUnits
         *
         * HALF_UP means: 0.12345 → 0.1235 (rounds up), 0.12344 → 0.1234 (rounds down)
         * This is different from HALF_EVEN (banker's rounding) which some systems use.
         * AMFI mandates HALF_UP for unit allotment.
         */
        @ParameterizedTest(name = "₹{0} / NAV {1} = {2} units")
        @CsvSource({
            "5000.00, 48.2345, 103.6602",
            "1000.00, 10.0000, 100.0000",
            "500.00,  48.2300, 10.3670",
            "100.00,   3.1415, 31.8319",
            "50000.00, 1.0000, 50000.0000",
        })
        void halvingRounding(String amount, String nav, String expected) {
            BigDecimal result = FinancialCalculations.calculateUnitsFromAmount(
                    new BigDecimal(amount), new BigDecimal(nav));
            assertThat(result).isEqualByComparingTo(new BigDecimal(expected));
        }

        @Test
        @DisplayName("throws IllegalArgumentException when amount is zero")
        void rejectsZeroAmount() {
            assertThatThrownBy(() ->
                FinancialCalculations.calculateUnitsFromAmount(BigDecimal.ZERO, new BigDecimal("48.23"))
            )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Purchase amount");
        }

        @Test
        @DisplayName("throws IllegalArgumentException when amount is negative")
        void rejectsNegativeAmount() {
            assertThatThrownBy(() ->
                FinancialCalculations.calculateUnitsFromAmount(new BigDecimal("-1000"), new BigDecimal("48.23"))
            )
            .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("throws IllegalArgumentException when NAV is zero")
        void rejectsZeroNav() {
            assertThatThrownBy(() ->
                FinancialCalculations.calculateUnitsFromAmount(new BigDecimal("5000"), BigDecimal.ZERO)
            )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("NAV value");
        }
    }

    // ─── calculateAmountFromUnits ─────────────────────────────────────────────

    @Nested
    @DisplayName("calculateAmountFromUnits")
    class CalculateAmountFromUnitsTests {

        @Test
        @DisplayName("multiplies units by NAV and rounds to 2 decimal places")
        void basicMultiplication() {
            BigDecimal units    = new BigDecimal("103.6765");
            BigDecimal navValue = new BigDecimal("48.2345");

            BigDecimal amount = FinancialCalculations.calculateAmountFromUnits(units, navValue);

            // 103.6765 × 48.2345 = 5000.78413925 → HALF_UP 2dp → 5000.78
            assertThat(amount.scale()).isEqualTo(2);
            assertThat(amount).isEqualByComparingTo(new BigDecimal("5000.78"));
        }

        @Test
        @DisplayName("result has exactly 2 decimal places (paise precision)")
        void scaleIs2() {
            BigDecimal result = FinancialCalculations.calculateAmountFromUnits(
                    new BigDecimal("100.0000"), new BigDecimal("50.0000"));
            assertThat(result.scale()).isEqualTo(2);
            assertThat(result).isEqualByComparingTo(new BigDecimal("5000.00"));
        }
    }

    // ─── calculateCurrentValue ────────────────────────────────────────────────

    @Nested
    @DisplayName("calculateCurrentValue")
    class CalculateCurrentValueTests {

        @Test
        @DisplayName("returns units × latestNav rounded to 2dp")
        void basicCurrentValue() {
            BigDecimal units = new BigDecimal("103.6765");
            BigDecimal nav   = new BigDecimal("52.1000"); // NAV went up from 48.23

            BigDecimal currentValue = FinancialCalculations.calculateCurrentValue(units, nav);

            // 103.6765 × 52.10 = 5401.54565 → 5401.55
            assertThat(currentValue).isEqualByComparingTo(new BigDecimal("5401.55"));
        }

        @Test
        @DisplayName("allows zero units (fully redeemed holding)")
        void allowsZeroUnits() {
            BigDecimal result = FinancialCalculations.calculateCurrentValue(
                    BigDecimal.ZERO, new BigDecimal("48.23"));
            assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("throws when NAV is zero (would produce meaningless result)")
        void rejectsZeroNav() {
            assertThatThrownBy(() ->
                FinancialCalculations.calculateCurrentValue(new BigDecimal("100"), BigDecimal.ZERO)
            ).isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ─── calculateAbsoluteReturn ──────────────────────────────────────────────

    @Nested
    @DisplayName("calculateAbsoluteReturn")
    class CalculateAbsoluteReturnTests {

        @Test
        @DisplayName("returns positive % when current > invested (profit)")
        void positiveReturn() {
            BigDecimal current  = new BigDecimal("12000.00");
            BigDecimal invested = new BigDecimal("10000.00");

            BigDecimal returnPct = FinancialCalculations.calculateAbsoluteReturn(current, invested);

            // (12000 - 10000) / 10000 × 100 = 20.0000%
            assertThat(returnPct).isEqualByComparingTo(new BigDecimal("20.0000"));
        }

        @Test
        @DisplayName("returns negative % when current < invested (loss)")
        void negativeReturn() {
            BigDecimal current  = new BigDecimal("8000.00");
            BigDecimal invested = new BigDecimal("10000.00");

            BigDecimal returnPct = FinancialCalculations.calculateAbsoluteReturn(current, invested);

            // (8000 - 10000) / 10000 × 100 = -20.0000%
            assertThat(returnPct).isEqualByComparingTo(new BigDecimal("-20.0000"));
        }

        @Test
        @DisplayName("returns 0% when current equals invested (breakeven)")
        void zeroReturn() {
            BigDecimal returnPct = FinancialCalculations.calculateAbsoluteReturn(
                    new BigDecimal("10000.00"), new BigDecimal("10000.00"));
            assertThat(returnPct).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("result has exactly 4 decimal places")
        void scaleIs4() {
            BigDecimal returnPct = FinancialCalculations.calculateAbsoluteReturn(
                    new BigDecimal("10123.45"), new BigDecimal("10000.00"));
            assertThat(returnPct.scale()).isEqualTo(4);
        }

        @Test
        @DisplayName("throws when investedAmount is zero (division by zero)")
        void rejectsZeroInvested() {
            assertThatThrownBy(() ->
                FinancialCalculations.calculateAbsoluteReturn(
                        new BigDecimal("10000"), BigDecimal.ZERO)
            ).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
