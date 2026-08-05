package com.mfplatform.mfplatform.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * FinancialCalculations contains pure static utility methods for financial math.
 *
 * WHY STATIC METHODS (not a @Service or @Component):
 * These methods are pure functions — same input always gives same output,
 * no DB access, no Spring beans, no side effects. Making them static means:
 *   - No injection needed (just call FinancialCalculations.calculateUnits(...))
 *   - Trivially unit-testable (just call the method directly, no mocking)
 *   - Clear signal to readers: "this is math, not business logic"
 *
 * ROUNDING MODE:
 * All calculations use HALF_UP rounding — this is the AMFI-published standard
 * for mutual fund unit allotment in India. HALF_UP means 0.12345 rounds to
 * 0.1235, not 0.1234. This is the "round half away from zero" convention,
 * matching how fund houses publish allotted units on account statements.
 *
 * SCALE:
 * Units are always expressed to 4 decimal places (AMFI standard).
 * Returns are expressed to 4 decimal places (basis points precision).
 * Amounts are expressed to 2 decimal places (paise precision).
 */
public final class FinancialCalculations {

    // Prevent instantiation — this is a utility class, not meant to be instantiated
    private FinancialCalculations() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Calculates units to allot for a purchase transaction.
     *
     * Formula: units = amount / NAV
     * Example: ₹5000 / NAV ₹48.2345 = 103.6765 units
     *
     * @param amount   the purchase amount in INR (must be positive)
     * @param navValue the applicable NAV value (must be positive)
     * @return allotted units, rounded to 4 decimal places using HALF_UP
     */
    public static BigDecimal calculateUnitsFromAmount(BigDecimal amount, BigDecimal navValue) {
        validatePositive(amount, "Purchase amount");
        validatePositive(navValue, "NAV value");
        return amount.divide(navValue, 4, RoundingMode.HALF_UP);
    }

    /**
     * Calculates the redemption amount for a given number of units.
     *
     * Formula: amount = units × NAV
     * Example: 50.5 units × NAV ₹48.2345 = ₹2435.84 (rounded to 2 decimal places)
     *
     * Used for redemption-by-units to determine how much the investor receives.
     *
     * @param units    the units to redeem (must be positive)
     * @param navValue the applicable NAV value (must be positive)
     * @return redemption amount in INR, rounded to 2 decimal places
     */
    public static BigDecimal calculateAmountFromUnits(BigDecimal units, BigDecimal navValue) {
        validatePositive(units, "Units");
        validatePositive(navValue, "NAV value");
        return units.multiply(navValue).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Calculates units to redeem for a redemption-by-amount request.
     *
     * Formula: units = amount / NAV (same as purchase, different semantic)
     * Example: redeem ₹10,000 worth at NAV ₹48.2345 = 207.3530 units redeemed
     *
     * @param amount   the redemption amount requested in INR (must be positive)
     * @param navValue the applicable NAV value (must be positive)
     * @return units to redeem, rounded to 4 decimal places using HALF_UP
     */
    public static BigDecimal calculateUnitsForRedemptionAmount(
            BigDecimal amount, BigDecimal navValue) {
        validatePositive(amount, "Redemption amount");
        validatePositive(navValue, "NAV value");
        return amount.divide(navValue, 4, RoundingMode.HALF_UP);
    }

    /**
     * Calculates current market value of a holding.
     *
     * Formula: current value = units held × latest NAV
     *
     * This is always calculated at query time — never stored in the Holding
     * table, because it changes every time a new NAV is imported. See ADR-002.
     *
     * @param unitsHeld  current units in the holding
     * @param latestNav  most recent NAV value for the scheme
     * @return current value in INR, rounded to 2 decimal places
     */
    public static BigDecimal calculateCurrentValue(BigDecimal unitsHeld, BigDecimal latestNav) {
        validateNonNegative(unitsHeld, "Units held");
        validatePositive(latestNav, "Latest NAV");
        return unitsHeld.multiply(latestNav).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Calculates absolute return as a percentage.
     *
     * Formula: ((current value - invested amount) / invested amount) × 100
     * Example: current ₹12,000, invested ₹10,000 → return = 20.0000%
     *
     * @param currentValue    current market value of the holding
     * @param investedAmount  total amount invested (sum of purchase amounts)
     * @return return percentage to 4 decimal places (e.g. 20.1234 means 20.1234%)
     */
    public static BigDecimal calculateAbsoluteReturn(
            BigDecimal currentValue, BigDecimal investedAmount) {
        validateNonNegative(currentValue, "Current value");
        validatePositive(investedAmount, "Invested amount");

        return currentValue
                .subtract(investedAmount)
                .divide(investedAmount, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(4, RoundingMode.HALF_UP);
    }

    // ─── Private guards ───────────────────────────────────────────────────────

    private static void validatePositive(BigDecimal value, String name) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(name + " must be positive, got: " + value);
        }
    }

    private static void validateNonNegative(BigDecimal value, String name) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException(name + " must be non-negative, got: " + value);
        }
    }
}
