package com.mfplatform.mfplatform.nav;

import com.mfplatform.mfplatform.scheme.Scheme;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * NavHistory stores the daily NAV (Net Asset Value) for each scheme.
 *
 * KEY DESIGN DECISIONS:
 *
 * 1. APPEND-ONLY — rows are never updated or deleted. Each day's NAV is a
 *    new row. This preserves the full history of NAV values, which is required
 *    for accurate historical return calculations and for the immutable transaction
 *    ledger (a transaction row references the exact nav_history row whose value
 *    was used for allotment — that value must never change).
 *
 * 2. UNIQUE(scheme_id, nav_date) — enforced both here via @Table(uniqueConstraints)
 *    and in the DB schema via a UNIQUE constraint. Prevents duplicate NAV imports
 *    for the same scheme on the same date. If NavImportService tries to insert
 *    a duplicate, it gets a DataIntegrityViolationException → 409 via GlobalExceptionHandler.
 *
 * 3. nav_value uses BigDecimal, not double — financial calculations must never
 *    use floating-point types. double cannot represent most decimal fractions
 *    exactly (e.g. 0.1 in binary floating point is actually 0.1000000000000000055...).
 *    BigDecimal gives exact decimal arithmetic, which matters when calculating
 *    units = amount / nav_value across thousands of transactions.
 *
 * Real-world context:
 * In a real AMC system, daily NAVs are published by the AMC to AMFI by 9 PM
 * each business day. NavImportService simulates that import process.
 */
@Entity
@Table(
    name = "nav_history",
    uniqueConstraints = @UniqueConstraint(columnNames = {"scheme_id", "nav_date"})
)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NavHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The scheme this NAV belongs to.
     * FetchType.LAZY means the scheme is not loaded from the DB unless you
     * explicitly call getScheme() — avoids unnecessary joins on queries that
     * only need nav_date or nav_value.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scheme_id", nullable = false)
    private Scheme scheme;

    /**
     * The date this NAV was published.
     * Note: LocalDate (not LocalDateTime) — NAV is a daily value, not timestamped
     * to the minute. The exact publication time is irrelevant; what matters is
     * which business day the NAV is for.
     */
    @Column(name = "nav_date", nullable = false)
    private LocalDate navDate;

    /**
     * The NAV value on this date.
     * precision=12, scale=4 matches the DB column NUMERIC(12,4):
     *   - Up to 8 digits before the decimal point (max NAV: 99,999,999)
     *   - 4 decimal places (standard for Indian MF NAVs)
     *
     * Example: a NAV of ₹48.2345 is stored as 48.2345 exactly.
     */
    @Column(name = "nav_value", nullable = false, precision = 12, scale = 4)
    private BigDecimal navValue;
}
