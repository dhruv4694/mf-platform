package com.mfplatform.mfplatform.portfolio.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Portfolio DTOs — all read-only, derived from holding + nav_history + transaction.
 *
 * Nothing here is stored in the DB. Every value is calculated at query time.
 * See ADR-002 for why we never persist current_value or returns.
 */
public class PortfolioDtos {

    /**
     * A single scheme position within a folio.
     * The building block of the portfolio view.
     */
    public record HoldingView(
            Long holdingId,
            Long schemeId,
            String schemeName,
            String schemeCode,
            String schemeCategory,

            // From holding table (the stored running total)
            BigDecimal unitsHeld,

            // Derived: investedAmount = SUM of ALLOTTED PURCHASE transactions
            // for this folio/scheme, minus REVERSED ones
            BigDecimal investedAmount,

            // Derived: currentValue = unitsHeld × latestNav.
            // NULL — not ZERO — when no NAV exists yet for this scheme as of the
            // current business date. Units held are always correct (only ever
            // updated at EOD settlement); current value is never stored, always
            // computed fresh at read time, and a missing NAV means "not known
            // yet", not "worth nothing". Collapsing that distinction into ZERO
            // is exactly what produced a false 100% loss on the dashboard.
            BigDecimal currentValue,

            // Derived: absoluteReturn = (currentValue - investedAmount) / investedAmount × 100
            // NULL whenever currentValue is null — a return percentage against
            // an unknown current value is meaningless, not just unavailable.
            BigDecimal absoluteReturnPct,

            // The NAV used for currentValue calculation — null under the same
            // condition as currentValue.
            BigDecimal latestNavValue,
            LocalDate latestNavDate
    ) {}

    /**
     * All holdings for a single folio, with folio-level totals.
     */
    public record FolioPortfolio(
            Long folioId,
            String folioNumber,

            // All scheme positions in this folio
            List<HoldingView> holdings,

            // Folio-level aggregates (sum across all holdings)
            BigDecimal totalInvestedAmount,
            BigDecimal totalCurrentValue,
            BigDecimal totalAbsoluteReturnPct
    ) {}

    /**
     * Complete portfolio for an investor — across all their folios.
     * Top-level view shown on the dashboard.
     */
    public record InvestorPortfolio(
            Long investorId,
            String investorName,

            // All folios with their holdings
            List<FolioPortfolio> folios,

            // Portfolio-level totals (sum across all folios)
            BigDecimal totalInvestedAmount,
            BigDecimal totalCurrentValue,
            BigDecimal totalAbsoluteReturnPct,

            // Asset allocation breakdown — useful for pie chart
            List<CategoryAllocation> allocationByCategory
    ) {}

    /**
     * Asset allocation for one scheme category.
     * e.g. EQUITY: ₹75,000 invested, 62.5% of portfolio
     */
    public record CategoryAllocation(
            String category,
            BigDecimal investedAmount,
            BigDecimal currentValue,
            BigDecimal allocationPct   // % of total portfolio current value
    ) {}

    /**
     * Summary for distributor dashboard — one entry per investor in their book.
     * Lets a distributor see their full book AUM at a glance.
     */
    public record InvestorSummary(
            Long investorId,
            String investorName,
            String email,
            BigDecimal totalInvestedAmount,
            BigDecimal totalCurrentValue,
            BigDecimal totalAbsoluteReturnPct,
            int activeSchemeCount,
            int activeSipCount
    ) {}
}
