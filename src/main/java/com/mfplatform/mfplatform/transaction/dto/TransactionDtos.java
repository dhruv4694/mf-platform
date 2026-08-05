package com.mfplatform.mfplatform.transaction.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * DTOs for the transaction module.
 *
 * Purchase and redemption have separate request records because their
 * required fields differ — purchase always needs an amount, redemption
 * needs either units OR amount (but not both). Having separate types
 * makes this explicit at the API level rather than using nullable fields
 * with a runtime check.
 *
 * Responses are unified — both purchase and redemption return the same
 * TransactionResponse shape since they share the same underlying entity.
 */
public class TransactionDtos {

    /**
     * Purchase request — investor buys units of a scheme.
     *
     * amount: how much INR to invest (e.g. ₹5000).
     *         Units are calculated at EOD settlement by UnitAllotmentService.
     *
     * idempotencyKey: client-generated UUID for the logical operation.
     *         If the same key is submitted twice (e.g. network retry),
     *         the second request returns the existing transaction, not a new one.
     */
    public record PurchaseRequest(
            @NotNull Long folioId,
            @NotNull Long schemeId,
            @NotNull @DecimalMin(value = "0.01", message = "Purchase amount must be positive")
            BigDecimal amount,
            @NotBlank String idempotencyKey
    ) {}

    /**
     * Redemption request — investor sells units back to the fund house.
     *
     * Two modes:
     *   1. Redemption by units: set units, leave amount null
     *      "Redeem exactly 50.5 units"
     *   2. Redemption by amount: set amount, leave units null
     *      "Redeem ₹10,000 worth of units (fund house calculates how many)"
     *
     * Exactly one of units/amount must be non-null — validated in RedemptionService
     * (not with annotations, since cross-field validation is cleaner in the service).
     */
    public record RedemptionRequest(
            @NotNull Long folioId,
            @NotNull Long schemeId,
            BigDecimal units,   // null for redemption-by-amount
            BigDecimal amount,  // null for redemption-by-units
            @NotBlank String idempotencyKey
    ) {}

    /**
     * Unified response for both purchase and redemption transactions.
     * allottedUnits and applicableNavValue are null until allotment completes.
     */
    public record TransactionResponse(
            Long id,
            String type,
            String status,
            Long folioId,
            Long schemeId,
            BigDecimal requestAmount,
            BigDecimal requestUnits,
            BigDecimal allottedUnits,
            BigDecimal applicableNavValue,
            String initiatedByRole,
            Instant requestedAt,
            Instant processedAt,
            LocalDate businessDate
    ) {}
}
