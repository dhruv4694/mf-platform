package com.mfplatform.mfplatform.transaction;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * HoldingService manages Holding rows — the running unit balance per
 * (folio, scheme) pair.
 *
 * KEY RESPONSIBILITY: getOrCreate()
 * When an investor purchases a scheme for the first time in a folio, no
 * Holding row exists yet. getOrCreate() either finds the existing row or
 * creates a new one with unitsHeld = 0. This is done safely with respect
 * to concurrent first-purchases (see getOrCreate() javadoc).
 *
 * HoldingService is NOT responsible for the allotment math or transaction
 * status updates — those belong in UnitAllotmentService. HoldingService
 * only manages the lifecycle of Holding rows themselves.
 */
@Service
public class HoldingService {

    private final HoldingRepository holdingRepository;

    public HoldingService(HoldingRepository holdingRepository) {
        this.holdingRepository = holdingRepository;
    }

    /**
     * Returns the existing Holding for this folio/scheme pair, or creates
     * a new one with unitsHeld = 0 if it doesn't exist yet.
     *
     * CONCURRENCY SAFETY:
     * Two concurrent first-purchases for the same folio/scheme could both
     * find no existing holding and both try to INSERT. The UNIQUE constraint
     * on (folio_id, scheme_id) prevents two rows from being created —
     * one INSERT will fail with DataIntegrityViolationException.
     *
     * We catch that exception and retry the find — the second thread's INSERT
     * failed because the first thread's INSERT succeeded, so a find will
     * now return that newly created row.
     *
     * This "optimistic insert" pattern (try to insert, catch conflict, retry
     * find) is simpler than a pessimistic SELECT FOR UPDATE and avoids
     * holding a row lock during the find.
     *
     * @param folioId  the folio to find/create the holding for
     * @param schemeId the scheme to find/create the holding for
     * @return the existing or newly created Holding, always non-null
     */
    @Transactional
    public Holding getOrCreate(Long folioId, Long schemeId) {
        return holdingRepository
                .findByFolioIdAndSchemeId(folioId, schemeId)
                .orElseGet(() -> {
                    try {
                        return holdingRepository.save(
                                Holding.builder()
                                        .folioId(folioId)
                                        .schemeId(schemeId)
                                        .unitsHeld(BigDecimal.ZERO)
                                        .build()
                        );
                    } catch (DataIntegrityViolationException e) {
                        // Another concurrent thread just created this holding.
                        // The UNIQUE constraint fired — find the row they created.
                        return holdingRepository
                                .findByFolioIdAndSchemeId(folioId, schemeId)
                                .orElseThrow(() -> new IllegalStateException(
                                        "Holding insert failed but row not found — " +
                                        "this should never happen. folio=" + folioId +
                                        " scheme=" + schemeId));
                    }
                });
    }

    /**
     * Returns the current unit balance for a folio/scheme pair.
     * Returns BigDecimal.ZERO if no holding exists yet (investor has never
     * purchased this scheme in this folio).
     *
     * Used by TransactionContext builder to populate currentUnitsHeld
     * before the validation chain runs.
     */
    public BigDecimal getCurrentUnits(Long folioId, Long schemeId) {
        return holdingRepository
                .findByFolioIdAndSchemeId(folioId, schemeId)
                .map(Holding::getUnitsHeld)
                .orElse(BigDecimal.ZERO);
    }
}
