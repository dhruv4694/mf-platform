package com.mfplatform.mfplatform.nav;

import com.mfplatform.mfplatform.nav.dto.NavDtos.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * NavController exposes NAV import and history endpoints.
 *
 * Access control:
 *   POST /nav/import          → ADMIN only (manual single NAV entry)
 *   POST /nav/simulate-bulk   → ADMIN only (historical data seeding)
 *   GET  /nav/schemes/{id}    → all authenticated roles (view NAV history for a scheme)
 *
 * The GET endpoint is open to all roles because:
 *   - Investors need to see a scheme's NAV history to make investment decisions
 *   - Distributors need it to advise their clients
 *   - It's publicly available data (AMFI publishes all NAVs publicly)
 */
@Tag(name = "NAV", description = "Import Net Asset Values for schemes. Admin only.")
@RestController
@RequestMapping("/api/v1/nav")
public class NavController {

    private final NavImportService navImportService;

    public NavController(NavImportService navImportService) {
        this.navImportService = navImportService;
    }

    /**
     * Imports a single NAV record for a specific scheme and date.
     * Idempotent — importing the same scheme+date again returns the existing record.
     */
    @PostMapping("/import")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<NavHistoryResponse> importNav(
            @Valid @RequestBody ImportNavRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(navImportService.importNav(request));
    }

    /**
     * Simulates bulk NAV generation for all schemes over a date range.
     * Useful for seeding a fresh database with realistic historical NAV data.
     * Weekends are automatically skipped (no NAVs on non-business days).
     */
    @PostMapping("/simulate-bulk")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BulkNavImportResponse> simulateBulkNavImport(
            @Valid @RequestBody BulkNavImportRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(navImportService.simulateBulkNavImport(request));
    }

    /**
     * Returns NAV history for a scheme.
     * Optional from/to query params filter by date range.
     *
     * Example:
     *   GET /api/v1/nav/schemes/1                          → full history
     *   GET /api/v1/nav/schemes/1?from=2026-01-01&to=2026-06-30  → 6-month history
     */
    @GetMapping("/schemes/{schemeId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<NavHistoryResponse>> getNavHistory(
            @PathVariable Long schemeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(navImportService.getNavHistory(schemeId, from, to));
    }
}
