package com.mfplatform.mfplatform.nav;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * NavHistoryRepository provides the DB queries needed by NavImportService
 * and PortfolioService.
 *
 * The most important query here is findLatestNavOnOrBefore() — this is what
 * PortfolioService calls to find the latest available NAV for valuation.
 * It returns the most recent NAV on or before a given date, which handles
 * weekends and market holidays correctly (on a Monday, the applicable NAV
 * might be Friday's, since markets are closed on weekends and no NAV is
 * published).
 */
public interface NavHistoryRepository extends JpaRepository<NavHistory, Long> {

    /**
     * Find the NAV for a specific scheme on a specific date.
     * Used by NavImportService to check if today's NAV already exists
     * before importing (avoiding a duplicate key exception).
     */
    Optional<NavHistory> findBySchemeIdAndNavDate(Long schemeId, LocalDate navDate);

    /**
     * Find the most recent NAV for a scheme on or before a given date.
     *
     * If today's NAV isn't published yet, or if the target date falls on a
     * weekend/holiday, this returns the last available NAV.
     *
     * ORDER BY nav_date DESC LIMIT 1 ensures we always get the most recent
     * applicable NAV, not an older one.
     *
     * Example:
     *   - Transaction on Monday 2026-07-06 before cutoff → eligible for 2026-07-06 NAV
     *   - Transaction on Monday 2026-07-06 after cutoff  → eligible for 2026-07-07 NAV
     *     (next business day, found by calling this with 2026-07-07 as targetDate)
     *   - But if 2026-07-07 is a holiday and no NAV was published → returns
     *     2026-07-06's NAV (the most recent available on or before 2026-07-07)
     */
    @Query("select n from NavHistory n " +
           "where n.scheme.id = :schemeId " +
           "and n.navDate <= :targetDate " +
           "order by n.navDate desc " +
           "limit 1")
    Optional<NavHistory> findLatestNavOnOrBefore(
            @Param("schemeId") Long schemeId,
            @Param("targetDate") LocalDate targetDate);

    /**
     * Find all NAV records for a scheme, sorted by date ascending.
     * Used for return calculations and displaying NAV history charts.
     */
    List<NavHistory> findBySchemeIdOrderByNavDateAsc(Long schemeId);

    /**
     * Find NAV records for a scheme within a date range.
     * Used for period-specific return calculations (e.g. 1-year return).
     */
    @Query("select n from NavHistory n " +
           "where n.scheme.id = :schemeId " +
           "and n.navDate between :from and :to " +
           "order by n.navDate asc")
    List<NavHistory> findBySchemeIdAndDateRange(
            @Param("schemeId") Long schemeId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);
}
