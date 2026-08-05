package com.mfplatform.mfplatform.transaction;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

/**
 * HoldingRepository provides DB access for holding rows.
 *
 * Key methods:
 *   findByFolioIdAndSchemeId — used by UnitAllotmentService to load the
 *     holding before updating it (with @Version optimistic lock)
 *
 *   findByFolioId — used by PortfolioService to list all holdings for a folio
 *
 * Note on locking:
 * We do NOT use @Lock(PESSIMISTIC_WRITE) here because we're using optimistic
 * locking (@Version) instead. Pessimistic locking would hold a DB row lock
 * for the duration of the allotment, reducing throughput. Optimistic locking
 * allows concurrent reads and only detects conflicts at write time.
 */
public interface HoldingRepository extends JpaRepository<Holding, Long> {

    /**
     * Finds the holding for a specific folio/scheme pair.
     * Returns Optional.empty() if the investor has never purchased this scheme
     * in this folio before (triggers getOrCreate in HoldingService).
     */
    Optional<Holding> findByFolioIdAndSchemeId(Long folioId, Long schemeId);

    /**
     * All holdings for a folio — used by PortfolioService to show an investor's
     * complete position across all schemes in a folio.
     */
    List<Holding> findByFolioId(Long folioId);

    /**
     * All holdings across all folios for a set of folio IDs.
     * Used by PortfolioService for distributor/admin portfolio views
     * covering multiple investors.
     */
    List<Holding> findByFolioIdIn(List<Long> folioIds);

    /**
     * All holdings for a specific scheme across all folios.
     * Used for scheme-level AUM (Assets Under Management) calculations:
     *   AUM = SUM(unitsHeld) × current NAV across all holdings for this scheme
     */
    @Query("select h from Holding h where h.schemeId = :schemeId and h.unitsHeld > 0")
    List<Holding> findActiveHoldingsBySchemeId(@Param("schemeId") Long schemeId);
}
