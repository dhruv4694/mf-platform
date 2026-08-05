package com.mfplatform.mfplatform.batch;

import com.mfplatform.mfplatform.sip.SipMandate;
import com.mfplatform.mfplatform.sip.SipMandateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.stereotype.Component;

/**
 * SipItemWriter persists the processed SIP mandates.
 *
 * SPRING BATCH ItemWriter CONTRACT:
 * The writer receives a Chunk<SipInstallmentResult> — a list of all results
 * from one chunk (10 items by default). It is responsible for persisting
 * the output of the processor. After the writer returns, Spring Batch
 * commits the chunk's transaction.
 *
 * WHAT WE WRITE:
 * The processor already called mandate.advanceNextDueDate() — the SipMandate
 * now holds the updated nextDueDate (and possibly status=COMPLETED if the
 * end date passed). The writer simply saves these updated mandates.
 *
 * BATCH SAVE (saveAll):
 * We collect all mandates from the chunk and call saveAll() once —
 * a single SQL batch INSERT/UPDATE instead of N individual queries.
 * This is the correct pattern for ItemWriter — never call save() in a loop.
 *
 * TRANSACTION BOUNDARY:
 * The write() call happens inside the chunk's transaction (managed by
 * Spring Batch's PlatformTransactionManager). If write() throws, the
 * entire chunk rolls back — none of the 10 mandates are saved.
 * Spring Batch then retries or skips per the fault tolerance configuration
 * in SipBatchJobConfig.
 */
@Component
public class SipItemWriter implements ItemWriter<SipInstallmentResult> {

    private static final Logger log = LoggerFactory.getLogger(SipItemWriter.class);

    private final SipMandateRepository sipMandateRepository;

    public SipItemWriter(SipMandateRepository sipMandateRepository) {
        this.sipMandateRepository = sipMandateRepository;
    }

    /**
     * Persists all processed SIP mandates in a single batch operation.
     *
     * Chunk<SipInstallmentResult> contains up to chunkSize items (10).
     * We extract the mandate from each result and save them all at once.
     */
    @Override
    public void write(Chunk<? extends SipInstallmentResult> chunk) {
        // Log summary for this chunk
        long succeeded = chunk.getItems().stream()
                .filter(SipInstallmentResult::success).count();
        long failed = chunk.size() - succeeded;

        log.info("Writing chunk of {} mandates | succeeded={} | failed={}",
                chunk.size(), succeeded, failed);

        // Extract the updated mandates and batch-save them
        java.util.List<SipMandate> mandatesToSave = chunk.getItems().stream()
                .map(SipInstallmentResult::mandate)
                .toList();

        sipMandateRepository.saveAll(mandatesToSave);

        // Log each failed installment for the ops team
        chunk.getItems().stream()
                .filter(r -> !r.success())
                .forEach(r -> log.warn(
                        "SIP installment bounce | mandateId={} | reason={}",
                        r.mandateId(), r.failureReason()));
    }
}
