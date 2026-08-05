package com.mfplatform.mfplatform.transaction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * EodProcessingService runs End-of-Day settlement: it finds every PENDING
 * transaction stamped with a given business date and settles it (payment →
 * NAV → allotment). This is what PurchaseService/RedemptionService used to do
 * synchronously inside the HTTP request — it now happens explicitly, once per
 * business date, exactly like a real AMC's EOD batch. Triggered by
 * AdminController (a production system would trigger it from a cron).
 *
 * NAV RESOLUTION — DELIBERATELY NOT NavEligibilityService:
 * EquityNavEligibilityStrategy/DebtNavEligibilityStrategy compute the
 * applicable NAV date from real-clock timestamps (transactionTime /
 * paymentRealizedTime) — that logic is about deciding which business date a
 * transaction lands on at REQUEST time. Once we're settling a specific,
 * already-assigned business date, the applicable NAV is simply "the NAV
 * published for this scheme on this business date" — using the cutoff
 * strategies here would let a stale, earlier NAV win (findLatestNavOnOrBefore
 * falls back), settling at the wrong price under an admin-advanced business
 * date. So the per-transaction work (in EodTransactionProcessor) looks up
 * NavHistory by (schemeId, businessDate) directly.
 *
 * "STAYS PENDING" WITHOUT A REVERSE STATE TRANSITION:
 * TransactionStatus is forward-only — there's no way back from PAYMENT_REALIZED
 * to PENDING. So the NAV lookup happens BEFORE any state is touched: if there's
 * no NAV for (scheme, businessDate), the transaction is left completely alone
 * (still PENDING) and counted under pendingNoNav. This is also what makes
 * reruns idempotent: a rerun's `findByStatusAndBusinessDate(PENDING, ...)`
 * query only ever returns rows nothing has touched yet.
 *
 * PER-TRANSACTION ISOLATION:
 * Each transaction is settled by EodTransactionProcessor.processOne(), a
 * separate bean whose method is REQUIRES_NEW — so one bad row (unexpected
 * exception) can't roll back the whole batch. See that class's javadoc for
 * why it has to be a separate bean rather than a method here.
 */
@Service
public class EodProcessingService {

    private static final Logger log = LoggerFactory.getLogger(EodProcessingService.class);

    private final MfTransactionRepository transactionRepository;
    private final EodTransactionProcessor eodTransactionProcessor;

    public EodProcessingService(
            MfTransactionRepository transactionRepository,
            EodTransactionProcessor eodTransactionProcessor) {
        this.transactionRepository = transactionRepository;
        this.eodTransactionProcessor = eodTransactionProcessor;
    }

    /**
     * Settles every PENDING transaction stamped with the given business date.
     *
     * @param businessDate the business date to settle (usually
     *                     BusinessDateService.today(), but callable for any
     *                     date — e.g. rerunning after a missed NAV import)
     * @return summary counts for the run
     */
    public EodSummary runEod(LocalDate businessDate) {
        List<MfTransaction> pending = transactionRepository
                .findByStatusAndBusinessDate(TransactionStatus.PENDING, businessDate);

        log.info("EOD run starting for businessDate {} — {} PENDING transaction(s) found",
                businessDate, pending.size());

        int allotted = 0;
        int failed = 0;
        int pendingNoNav = 0;

        for (MfTransaction transaction : pending) {
            EodOutcome outcome;
            try {
                outcome = eodTransactionProcessor.processOne(transaction.getId(), businessDate);
            } catch (Exception ex) {
                log.error("EOD: unexpected error settling transaction {} — counting as failed",
                        transaction.getId(), ex);
                outcome = EodOutcome.FAILED;
            }

            switch (outcome) {
                case ALLOTTED -> allotted++;
                case FAILED -> failed++;
                case PENDING_NO_NAV -> pendingNoNav++;
            }
        }

        log.info("EOD run complete for businessDate {} — processed={} allotted={} failed={} pendingNoNav={}",
                businessDate, pending.size(), allotted, failed, pendingNoNav);

        return new EodSummary(pending.size(), allotted, failed, pendingNoNav);
    }

    /**
     * Summary of one EOD run.
     *
     * @param processed     total PENDING transactions examined this run
     * @param allotted      settled successfully
     * @param failed        settlement attempted but failed (or an unexpected error occurred)
     * @param pendingNoNav  left untouched — no NAV published yet for their scheme/businessDate
     */
    public record EodSummary(int processed, int allotted, int failed, int pendingNoNav) {}
}
