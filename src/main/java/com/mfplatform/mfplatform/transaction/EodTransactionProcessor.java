package com.mfplatform.mfplatform.transaction;

import com.mfplatform.mfplatform.nav.NavHistory;
import com.mfplatform.mfplatform.nav.NavHistoryRepository;
import com.mfplatform.mfplatform.notification.event.ApplicationEvents.TransactionSettledEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;

/**
 * EodTransactionProcessor settles exactly one PENDING transaction, in its own
 * REQUIRES_NEW transaction.
 *
 * WHY A SEPARATE BEAN (not just a @Transactional method on EodProcessingService):
 * Spring's @Transactional works via a proxy around the bean. A call from one
 * method to another method on the SAME bean instance ("self-invocation")
 * bypasses that proxy entirely — the annotation would be silently ignored.
 * EodProcessingService.runEod() loops and calls processOne() once per
 * transaction; for REQUIRES_NEW to actually create an isolated transaction
 * per row (so one bad row can't roll back the whole EOD run), processOne()
 * has to live on a different bean that EodProcessingService calls through.
 *
 * NAV RESOLUTION and the "stays PENDING" guarantee are documented on
 * EodProcessingService — this class is just where the per-row transaction
 * boundary lives.
 */
@Service
public class EodTransactionProcessor {

    private static final Logger log = LoggerFactory.getLogger(EodTransactionProcessor.class);

    private final MfTransactionRepository transactionRepository;
    private final NavHistoryRepository navHistoryRepository;
    private final PaymentSimulationService paymentSimulationService;
    private final UnitAllotmentService unitAllotmentService;
    private final ApplicationEventPublisher eventPublisher;

    public EodTransactionProcessor(
            MfTransactionRepository transactionRepository,
            NavHistoryRepository navHistoryRepository,
            PaymentSimulationService paymentSimulationService,
            UnitAllotmentService unitAllotmentService,
            ApplicationEventPublisher eventPublisher) {
        this.transactionRepository = transactionRepository;
        this.navHistoryRepository = navHistoryRepository;
        this.paymentSimulationService = paymentSimulationService;
        this.unitAllotmentService = unitAllotmentService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Settles a single PENDING transaction.
     *
     * NAV pre-check happens first and touches nothing if it fails: if there's
     * no NavHistory row for (scheme, businessDate), the transaction is left
     * completely alone — still PENDING — and EodOutcome.PENDING_NO_NAV is
     * returned. Only once NAV is confirmed present do we run payment
     * simulation (mirroring PurchaseService's/RedemptionService's old
     * request-time step) → applyNav (using the business-date NAV directly,
     * not NavEligibilityService) → claim → allot.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public EodOutcome processOne(Long transactionId, LocalDate businessDate) {
        MfTransaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new IllegalStateException("Transaction not found: " + transactionId));

        Optional<NavHistory> nav = navHistoryRepository
                .findBySchemeIdAndNavDate(transaction.getSchemeId(), businessDate);

        if (nav.isEmpty()) {
            log.info("EOD: no NAV for scheme {} on {} — transaction {} stays PENDING",
                    transaction.getSchemeId(), businessDate, transactionId);
            return EodOutcome.PENDING_NO_NAV;
        }

        // ── Payment (mirrors PurchaseService/RedemptionService's old request-time step) ──
        if (transaction.getType() == TransactionType.PURCHASE) {
            paymentSimulationService.initiatePayment(transactionId);
            paymentSimulationService.simulateInstantRealization(transactionId);
        } else {
            transaction.transitionTo(TransactionStatus.PAYMENT_REALIZED);
            transactionRepository.save(transaction);
        }

        // ── Apply the business-date NAV directly (no NavEligibilityService) ──
        transaction = transactionRepository.findById(transactionId).orElseThrow();
        transaction.applyNav(nav.get());
        transactionRepository.save(transaction);

        // ── Claim + allot ──
        AllotmentResult result = unitAllotmentService.allot(transactionId);

        if (result instanceof AllotmentResult.Success s) {
            log.info("EOD: transaction {} allotted {} units at NAV {}",
                    transactionId, s.allottedUnits(), s.applicableNavValue());
            eventPublisher.publishEvent(new TransactionSettledEvent(
                    this, transactionRepository.findById(transactionId).orElseThrow(), null));
            return EodOutcome.ALLOTTED;
        } else if (result instanceof AllotmentResult.Failed f) {
            log.error("EOD: transaction {} allotment failed: {}", transactionId, f.reason());
            eventPublisher.publishEvent(new TransactionSettledEvent(
                    this, transactionRepository.findById(transactionId).orElseThrow(), f.reason()));
            return EodOutcome.FAILED;
        } else if (result instanceof AllotmentResult.Pending p) {
            // Unexpected in EOD's single-threaded batch loop — nothing else
            // claims transactions concurrently here. Count as failed for this
            // run rather than silently swallowing it.
            log.warn("EOD: transaction {} allotment pending (unexpected): {}", transactionId, p.reason());
            return EodOutcome.FAILED;
        }

        // Unreachable — sealed interface, all cases handled above
        throw new IllegalStateException("Unhandled AllotmentResult type for transaction " + transactionId);
    }
}
