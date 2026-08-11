package com.mfplatform.mfplatform.transaction;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * MfTransactionRepository provides all DB operations for transactions.
 *
 * The most critical method here is claimForAllotment() — the compare-and-set
 * operation that ensures only one worker processes any given transaction.
 * See ADR-003 for the full reasoning.
 */
public interface MfTransactionRepository extends JpaRepository<MfTransaction, Long> {

    /**
     * Idempotency lookup — before creating a new transaction, check if one
     * with this key already exists. If so, return it instead of inserting.
     */
    Optional<MfTransaction> findByIdempotencyKey(String idempotencyKey);

    /**
     * All transactions for a given folio, business date descending.
     * Not currently called (TransactionService uses the multi-folio variant
     * below even for a single-folio INVESTOR) — kept in sync with the same
     * businessDate-descending convention regardless.
     *
     * Sorted by businessDate, not requestedAt/creation order — this project
     * reasons about time purely in terms of businessDate everywhere else
     * (EOD settlement, NAV lookup, SIP due dates), and businessDate can move
     * backward (BusinessDateService.advance() permits it), so a transaction
     * created later in real time can legitimately have an earlier businessDate
     * than one created before it.
     */
    Page<MfTransaction> findByFolioIdOrderByBusinessDateDesc(Long folioId, Pageable pageable);

    /**
     * All transactions for a set of folios, business date descending.
     * Used by DISTRIBUTOR role (their full client book) and ADMIN
     * in GET /transactions/my. See findByFolioIdOrderByBusinessDateDesc()
     * for why businessDate, not requestedAt.
     */
    Page<MfTransaction> findByFolioIdInOrderByBusinessDateDesc(
            List<Long> folioIds, Pageable pageable);

    /**
     * THE COMPARE-AND-SET CLAIM OPERATION (see ADR-003).
     *
     * Atomically moves a transaction from NAV_APPLIED to ALLOTMENT_IN_PROGRESS.
     * Returns 1 if the claim succeeded (this worker now owns the transaction),
     * 0 if another worker already claimed it (or status changed for any reason).
     *
     * This is a database-level atomic operation — the WHERE clause IS the lock.
     * Only one concurrent UPDATE can match the same row with the same WHERE condition.
     *
     * @Modifying is required for any UPDATE/DELETE query in Spring Data JPA.
     * clearAutomatically = true flushes the entity cache after the update,
     * preventing stale entity reads immediately after the claim.
     *
     * Usage in UnitAllotmentService:
     *   int claimed = repository.claimForAllotment(txnId);
     *   if (claimed == 0) return; // another worker owns it
     *   // proceed with holding update — we have exclusive ownership
     */
    @Modifying(clearAutomatically = true)
    @Query("""
        update MfTransaction t
           set t.status = com.mfplatform.mfplatform.transaction.TransactionStatus.ALLOTMENT_IN_PROGRESS
         where t.id = :transactionId
           and t.status = com.mfplatform.mfplatform.transaction.TransactionStatus.NAV_APPLIED
        """)
    int claimForAllotment(@Param("transactionId") Long transactionId);

    /**
     * Finds all transactions in a given status stamped with a given business date.
     * The core query for EodProcessingService — finds all PENDING transactions
     * for a business date, across every scheme, to settle in one EOD run.
     */
    List<MfTransaction> findByStatusAndBusinessDate(TransactionStatus status, LocalDate businessDate);

    /**
     * All active (non-terminal) transactions for a folio/scheme pair.
     * Used by SufficientUnitsValidator to check if pending redemptions would
     * exceed available units when combined with already-committed redemptions.
     */
    @Query("""
        select t from MfTransaction t
         where t.folioId = :folioId
           and t.schemeId = :schemeId
           and t.status not in (
               com.mfplatform.mfplatform.transaction.TransactionStatus.FAILED,
               com.mfplatform.mfplatform.transaction.TransactionStatus.REVERSED
           )
        """)
    List<MfTransaction> findActiveForFolioAndScheme(
            @Param("folioId") Long folioId,
            @Param("schemeId") Long schemeId);
}
