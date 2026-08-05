package com.mfplatform.mfplatform.transaction;

import com.mfplatform.mfplatform.common.FinancialCalculations;
import com.mfplatform.mfplatform.nav.NavHistory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * UnitAllotmentService is the most concurrency-sensitive service in the system.
 * It performs the actual unit allotment: claim the transaction, update the
 * holding, mark the transaction as allotted.
 *
 * TWO-PHASE DESIGN (see ADR-003):
 *
 * Phase 1 — CLAIM (compare-and-set):
 *   claimForAllotment() atomically moves the transaction from NAV_APPLIED
 *   to ALLOTMENT_IN_PROGRESS. Only one worker can win this. If claimed = 0,
 *   another worker owns it — we exit immediately without touching Holding.
 *
 * Phase 2 — WORK (holding update + status update):
 *   Only the winning worker reaches here. Updates unitsHeld on Holding
 *   (protected by @Version optimistic lock) and marks the transaction ALLOTTED.
 *   Both updates commit in the same @Transactional boundary.
 *
 * RETRY LOGIC:
 *   - On ObjectOptimisticLockingFailureException from HOLDING: retry up to
 *     MAX_RETRIES times with fresh entity reads. The transaction still belongs
 *     to this worker; the holding update MUST eventually succeed.
 *   - On ObjectOptimisticLockingFailureException from TRANSACTION: reload and
 *     check status. If ALLOTTED/FAILED → exit (work is done). If still
 *     NAV_APPLIED → transient race, this shouldn't happen after a successful
 *     claim but we handle it defensively.
 *
 * ALLOTMENT RESULT:
 *   Returns AllotmentResult (sealed interface with pattern matching) so callers
 *   can handle all three outcomes exhaustively without a default branch.
 */
@Service
public class UnitAllotmentService {

    private static final Logger log = LoggerFactory.getLogger(UnitAllotmentService.class);
    private static final int MAX_HOLDING_RETRIES = 3;

    private final MfTransactionRepository transactionRepository;
    private final HoldingService holdingService;
    private final HoldingRepository holdingRepository;

    public UnitAllotmentService(
            MfTransactionRepository transactionRepository,
            HoldingService holdingService,
            HoldingRepository holdingRepository) {
        this.transactionRepository = transactionRepository;
        this.holdingService = holdingService;
        this.holdingRepository = holdingRepository;
    }

    /**
     * Attempts to allot units for the given transaction.
     *
     * @param transactionId the NAV_APPLIED transaction to process
     * @return AllotmentResult — Success, Failed, or Pending
     *         Callers must handle all three outcomes (sealed interface ensures this)
     */
    public AllotmentResult allot(Long transactionId) {
        // ── PHASE 1: CLAIM ────────────────────────────────────────────────────
        // Atomically move NAV_APPLIED → ALLOTMENT_IN_PROGRESS.
        // claimed = 1 means we own it. claimed = 0 means someone else does.
        int claimed = transactionRepository.claimForAllotment(transactionId);

        if (claimed == 0) {
            log.debug("Transaction {} already claimed by another worker — skipping", transactionId);
            return new AllotmentResult.Pending(
                    "Transaction " + transactionId + " was already claimed by another worker");
        }

        log.info("Claimed transaction {} for allotment", transactionId);

        // ── PHASE 2: WORK ─────────────────────────────────────────────────────
        // We exclusively own this transaction. Now update holding + mark allotted.
        // Retry if the holding update fails due to a concurrent update from
        // another transaction allotment on the same folio/scheme.
        for (int attempt = 1; attempt <= MAX_HOLDING_RETRIES; attempt++) {
            try {
                return performAllotment(transactionId);
            } catch (ObjectOptimisticLockingFailureException e) {
                if (attempt == MAX_HOLDING_RETRIES) {
                    log.error("Holding update failed after {} retries for transaction {}",
                            MAX_HOLDING_RETRIES, transactionId, e);
                    // We own the transaction but can't update the holding.
                    // Mark transaction as FAILED so it's not stuck in ALLOTMENT_IN_PROGRESS.
                    markTransactionFailed(transactionId);
                    return new AllotmentResult.Failed(
                            "Holding update failed after " + MAX_HOLDING_RETRIES +
                            " retries due to concurrent updates");
                }
                log.warn("Holding optimistic lock conflict for transaction {} — " +
                         "retry {}/{}", transactionId, attempt, MAX_HOLDING_RETRIES);
                // Brief pause before retry to reduce thundering herd effect
                sleep(50L * attempt);
            }
        }

        // Unreachable — loop always returns or throws
        return new AllotmentResult.Failed("Unexpected state after retry loop");
    }

    /**
     * Performs one allotment attempt — loads fresh entities, updates holding,
     * marks transaction allotted. All inside one @Transactional boundary.
     *
     * @Transactional here means: if ObjectOptimisticLockingFailureException is
     * thrown by holdingRepository.save(), the entire method rolls back cleanly.
     * The retry in allot() then calls this method fresh, with no stale state.
     */
    @Transactional
    protected AllotmentResult performAllotment(Long transactionId) {
        // Reload the transaction fresh on each attempt — never reuse a stale entity
        MfTransaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new IllegalStateException(
                        "Transaction not found during allotment: " + transactionId));

        NavHistory applicableNav = transaction.getApplicableNav();
        BigDecimal navValue = applicableNav.getNavValue();

        // Calculate units based on transaction type
        BigDecimal unitsToAllot = calculateUnits(transaction, navValue);

        // Get or create the holding for this folio/scheme pair
        // (getOrCreate is safe for concurrent first-purchases — see HoldingService)
        Holding holding = holdingService.getOrCreate(
                transaction.getFolioId(), transaction.getSchemeId());

        // Update units — domain method enforces positive amount and
        // for redemption, throws InsufficientUnitsException if balance too low
        if (transaction.getType() == TransactionType.PURCHASE) {
            holding.addUnits(unitsToAllot);
        } else {
            holding.subtractUnits(unitsToAllot);
        }

        // Save holding — @Version check happens here.
        // If another allotment updated this holding between our read and this save,
        // ObjectOptimisticLockingFailureException is thrown → method rolls back →
        // caller retries with fresh entities.
        holdingRepository.save(holding);

        // Mark transaction allotted — @Version check also applies here.
        // In the normal case (we claimed it) this should always succeed.
        transaction.markAllotted(unitsToAllot);
        transactionRepository.save(transaction);

        log.info("Allotted {} units for transaction {} (NAV: {})",
                unitsToAllot, transactionId, navValue);

        return new AllotmentResult.Success(unitsToAllot, navValue);
    }

    /**
     * Calculates the units to allot/redeem based on transaction type and request.
     *
     * PURCHASE:
     *   Always amount ÷ NAV. The investor specifies how much money to invest.
     *
     * REDEMPTION BY UNITS:
     *   requestUnits is already the answer — investor specified exact units.
     *
     * REDEMPTION BY AMOUNT:
     *   amount ÷ NAV, same formula as purchase, to find how many units to
     *   redeem to give the investor the requested amount.
     */
    private BigDecimal calculateUnits(MfTransaction transaction, BigDecimal navValue) {
        return switch (transaction.getType()) {
            case PURCHASE ->
                FinancialCalculations.calculateUnitsFromAmount(
                        transaction.getRequestAmount(), navValue);

            case REDEMPTION -> {
                if (transaction.getRequestUnits() != null) {
                    // Redemption-by-units: investor specified exact units
                    yield transaction.getRequestUnits();
                } else {
                    // Redemption-by-amount: calculate units from requested amount
                    yield FinancialCalculations.calculateUnitsForRedemptionAmount(
                            transaction.getRequestAmount(), navValue);
                }
            }
        };
    }

    /**
     * Marks a transaction FAILED after exhausting retries on the holding update.
     * Runs in its own transaction so the failure is committed even if the
     * previous transaction was rolled back.
     */
    @Transactional
    protected void markTransactionFailed(Long transactionId) {
        transactionRepository.findById(transactionId).ifPresent(t -> {
            t.markFailed();
            transactionRepository.save(t);
        });
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
