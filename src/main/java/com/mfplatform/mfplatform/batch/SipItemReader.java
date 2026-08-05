package com.mfplatform.mfplatform.batch;

import com.mfplatform.mfplatform.common.BusinessDateService;
import com.mfplatform.mfplatform.sip.SipMandate;
import com.mfplatform.mfplatform.sip.SipMandateStatus;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.database.JpaPagingItemReader;
import org.springframework.batch.item.database.builder.JpaPagingItemReaderBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * SipItemReader reads SipMandate rows due for processing today.
 *
 * WHY JpaPagingItemReader:
 * Spring Batch's chunk-oriented model reads items in pages (chunks).
 * JpaPagingItemReader executes the same JPQL query repeatedly, advancing
 * the offset by pageSize on each call. This means:
 *   - Only pageSize rows are in memory at a time (not all due mandates)
 *   - Each page is read inside its own transaction
 *   - If the job restarts, it re-reads from the current page (tracked by
 *     Spring Batch in BATCH_STEP_EXECUTION_CONTEXT)
 *
 * WHY NOT JdbcCursorItemReader:
 * JdbcCursorItemReader holds an open DB cursor for the entire job duration.
 * For a job that processes each item slowly (payment simulation, allotment),
 * this risks cursor timeout. JpaPagingItemReader uses separate queries per page
 * which is safer for long-running jobs.
 *
 * PAGE SIZE = CHUNK SIZE:
 * We set pageSize to match the chunk size configured in SipBatchJobConfig (10).
 * If they differ, the reader might re-execute queries unnecessarily.
 *
 * QUERY:
 * Same logic as SipMandateRepository.findDueMandates() — ACTIVE mandates
 * with nextDueDate <= today. We use JPQL here because JpaPagingItemReader
 * requires it (it can't use Spring Data repository methods).
 *
 * NOTE ON :runDate PARAMETER:
 * The run date is passed as a job parameter (see SipBatchJobConfig) and
 * set on the reader at step execution time via @StepScope.
 * This makes the reader stateless and testable with any date.
 */
@Configuration
public class SipItemReader {

    private final EntityManagerFactory entityManagerFactory;
    private final BusinessDateService businessDateService;

    public SipItemReader(EntityManagerFactory entityManagerFactory, BusinessDateService businessDateService) {
        this.entityManagerFactory = entityManagerFactory;
        this.businessDateService = businessDateService;
    }

    /**
     * Reads SipMandate rows due on or before the job's run date.
     *
     * @StepScope on the @Bean means a new instance is created per step execution —
     * required so that businessDateService.today() is re-evaluated on every job
     * run instead of being baked in once at application startup. Without it,
     * "runDate" would freeze at whatever the business date was when this
     * singleton bean was first constructed, and advancing the business date
     * later would have no effect on which mandates are due.
     */
    @Bean
    @StepScope
    public JpaPagingItemReader<SipMandate> sipMandateItemReader() {
        return new JpaPagingItemReaderBuilder<SipMandate>()
                .name("sipMandateItemReader")
                .entityManagerFactory(entityManagerFactory)
                .queryString("""
                    SELECT m FROM SipMandate m
                     WHERE m.status = :status
                       AND m.nextDueDate <= :runDate
                     ORDER BY m.id ASC
                    """)
                .parameterValues(Map.of(
                        "status",  SipMandateStatus.ACTIVE,
                        "runDate", businessDateService.today()
                ))
                .pageSize(10) // must match chunk size in SipBatchJobConfig
                .saveState(true) // enables restart from last committed page
                .build();
    }
}
