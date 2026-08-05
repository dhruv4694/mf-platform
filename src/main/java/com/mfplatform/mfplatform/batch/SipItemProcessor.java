package com.mfplatform.mfplatform.batch;

import com.mfplatform.mfplatform.common.BusinessDateService;
import com.mfplatform.mfplatform.sip.SipMandate;
import com.mfplatform.mfplatform.transaction.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * SipItemProcessor processes one SipMandate per call.
 *
 * SPRING BATCH ItemProcessor CONTRACT:
 *   Input:  SipMandate (read by SipItemReader)
 *   Output: SipInstallmentResult (written by SipItemWriter)
 *   Return null to FILTER the item — the writer will not receive it.
 *   (We never return null here — we always produce a result.)
 *
 * WHAT PROCESS() DOES:
 *   1. Creates a PENDING PURCHASE transaction for this installment, stamped
 *      with the platform's current business date
 *   2. Advances nextDueDate
 *   3. Returns SipInstallmentResult carrying the updated mandate
 *
 * SETTLEMENT MOVED TO EOD:
 * Payment simulation (including the old 10% simulated-bounce behavior) and
 * allotment used to happen right here, per installment. They've moved to
 * EodProcessingService, which settles every PENDING PURCHASE the same way
 * regardless of whether it came from a SIP installment or a one-off purchase
 * request — so the SIP-specific bounce simulation is gone rather than being
 * duplicated inside EOD. Installments now always advance nextDueDate and wait
 * for EOD to settle them, same as every other transaction.
 *
 * NOTE — NO @Transactional HERE:
 * Spring Batch manages transactions at the chunk level (in SipBatchJobConfig).
 * The chunk's transaction wraps: read(10) → process(10) → write(10) → commit.
 * Adding @Transactional here would create a nested transaction per item
 * which is usually not what you want in a batch job — it defeats the
 * chunk-level commit strategy.
 */
@Component
public class SipItemProcessor implements ItemProcessor<SipMandate, SipInstallmentResult> {

    private static final Logger log = LoggerFactory.getLogger(SipItemProcessor.class);

    private final MfTransactionRepository transactionRepository;
    private final BusinessDateService businessDateService;

    public SipItemProcessor(
            MfTransactionRepository transactionRepository,
            BusinessDateService businessDateService) {
        this.transactionRepository = transactionRepository;
        this.businessDateService = businessDateService;
    }

    @Override
    public SipInstallmentResult process(SipMandate mandate) {
        log.info("Processing SIP mandate {} | folio={} | scheme={} | amount={}",
                mandate.getId(), mandate.getFolioId(),
                mandate.getSchemeId(), mandate.getAmount());

        // Step 1: Create the PENDING PURCHASE transaction for this installment.
        // EodProcessingService settles it later, exactly like any other purchase.
        MfTransaction transaction = transactionRepository.save(
                MfTransaction.builder()
                        .folioId(mandate.getFolioId())
                        .schemeId(mandate.getSchemeId())
                        .type(TransactionType.PURCHASE)
                        .status(TransactionStatus.PENDING)
                        .requestAmount(mandate.getAmount())
                        .idempotencyKey(buildIdempotencyKey(mandate))
                        .initiatedByUserId(mandate.getInitiatedByUserId())
                        .initiatedByRole(InitiatedByRole.INVESTOR)
                        .sipMandateId(mandate.getId())
                        .businessDate(businessDateService.today())
                        .build()
        );

        // Step 2: Advance schedule
        mandate.advanceNextDueDate();

        log.info("SIP mandate {} | created PENDING transaction {} | businessDate {}",
                mandate.getId(), transaction.getId(), transaction.getBusinessDate());

        return SipInstallmentResult.succeeded(mandate, transaction.getId());
    }

    /**
     * Deterministic idempotency key for a SIP installment.
     * Same mandate + same due date → same key → duplicate runs produce the same key
     * → TransactionService idempotency check returns existing transaction.
     */
    private String buildIdempotencyKey(SipMandate mandate) {
        return "SIP-" + mandate.getId()
                + "-" + mandate.getNextDueDate()
                + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
