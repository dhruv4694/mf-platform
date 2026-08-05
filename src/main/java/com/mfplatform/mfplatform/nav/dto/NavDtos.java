package com.mfplatform.mfplatform.nav.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * DTOs for the NAV module.
 * Kept in a single file since the records are small and closely related.
 */
public class NavDtos {

    /**
     * Request to import a single NAV for a specific scheme and date.
     * Used by ADMIN for manual NAV entry or testing.
     */
    public record ImportNavRequest(
            @NotNull Long schemeId,
            @NotNull LocalDate navDate,
            @NotNull @DecimalMin(value = "0.0001", message = "NAV must be greater than zero")
            BigDecimal navValue
    ) {}

    /**
     * Request to simulate bulk NAV generation for all schemes over a date range.
     * Used for seeding historical data on a fresh database.
     *
     * baseNav: the starting NAV value for all schemes (each then diverges
     * via a random walk, so they'll all be different by the end of the range).
     */
    public record BulkNavImportRequest(
            @NotNull LocalDate fromDate,
            @NotNull LocalDate toDate,
            @NotNull @DecimalMin(value = "0.0001", message = "Base NAV must be greater than zero")
            BigDecimal baseNav
    ) {}

    /**
     * Response after importing a NAV record.
     */
    public record NavHistoryResponse(
            Long id,
            Long schemeId,
            LocalDate navDate,
            BigDecimal navValue
    ) {}

    /**
     * Response after a bulk NAV simulation — summary of what was created.
     */
    public record BulkNavImportResponse(
            int recordsCreated,
            LocalDate fromDate,
            LocalDate toDate
    ) {}
}
