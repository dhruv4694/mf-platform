package com.mfplatform.mfplatform.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.*;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.database.JpaPagingItemReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import com.mfplatform.mfplatform.sip.SipMandate;
import com.mfplatform.mfplatform.transaction.validation.TransactionValidationException;

/**
 * SipBatchJobConfig defines the Spring Batch Job for daily SIP processing.
 *
 * JOB STRUCTURE:
 *   sipDailyJob
 *     └── sipProcessingStep
 *           ├── reader:    SipItemReader     (reads due mandates 10 at a time)
 *           ├── processor: SipItemProcessor  (payment + allotment per mandate)
 *           └── writer:    SipItemWriter     (saves updated mandates, batch)
 *
 * KEY CONCEPTS DEMONSTRATED:
 *
 * 1. CHUNK-ORIENTED PROCESSING (chunk(10)):
 *    Read 10 mandates → process each → write all 10 → commit.
 *    Repeat until no more mandates. If the job crashes after committing
 *    chunk 3, restart reads from chunk 4 (Spring Batch tracks this in
 *    BATCH_STEP_EXECUTION_CONTEXT). This is impossible with our old
 *    @Scheduled loop which had no restart capability.
 *
 * 2. FAULT TOLERANCE (.faultTolerant()):
 *    If processing one mandate fails (e.g. its folio was deleted),
 *    we want to SKIP that mandate and continue — not fail the entire job.
 *    .skip(TransactionValidationException.class) — skip invalid mandates
 *    .skipLimit(10) — but if more than 10 skip, something is systematically
 *    wrong → fail the job so ops team investigates.
 *    .noRetry(TransactionValidationException.class) — validation errors won't
 *    succeed on retry, so don't waste time retrying them.
 *
 * 3. JOB LISTENER (SipJobExecutionListener):
 *    Logs job start/end/status. In production you'd also send an alert
 *    if the job fails (PagerDuty, Slack webhook, etc.)
 *
 * 4. JOB PARAMETERS (used by SipBatchJobLauncher):
 *    Each job run has a unique runDate parameter so Spring Batch creates
 *    a new BATCH_JOB_INSTANCE per day (rather than treating every run as
 *    a re-run of the same job). This is required for the daily scheduling
 *    to work correctly with Spring Batch's job uniqueness checks.
 *
 * WHY JobRepository AND PlatformTransactionManager:
 *    JobRepository is Spring Batch's persistence layer — it saves job/step
 *    execution state to the BATCH_* tables. PlatformTransactionManager
 *    is injected into StepBuilder to manage the chunk transaction boundary.
 *    Both are auto-configured by spring-boot-starter-batch.
 */
@Configuration
public class SipBatchJobConfig {

    private static final Logger log = LoggerFactory.getLogger(SipBatchJobConfig.class);

    private static final int CHUNK_SIZE = 10;
    private static final int SKIP_LIMIT = 10; // fail job if > 10 mandates skipped

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final JpaPagingItemReader<SipMandate> sipMandateItemReader;
    private final SipItemProcessor sipItemProcessor;
    private final SipItemWriter sipItemWriter;

    public SipBatchJobConfig(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            JpaPagingItemReader<SipMandate> sipMandateItemReader,
            SipItemProcessor sipItemProcessor,
            SipItemWriter sipItemWriter) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.sipMandateItemReader = sipMandateItemReader;
        this.sipItemProcessor = sipItemProcessor;
        this.sipItemWriter = sipItemWriter;
    }

    /**
     * The Step: reads → processes → writes SIP mandates in chunks of 10.
     *
     * StepBuilder.<InputType, OutputType>chunk(size, transactionManager)
     *   InputType  = SipMandate (what the reader produces)
     *   OutputType = SipInstallmentResult (what the processor produces,
     *                and what the writer receives)
     */
    @Bean
    public Step sipProcessingStep() {
        return new StepBuilder("sipProcessingStep", jobRepository)
                .<SipMandate, SipInstallmentResult>chunk(CHUNK_SIZE, transactionManager)
                .reader(sipMandateItemReader)
                .processor(sipItemProcessor)
                .writer(sipItemWriter)
                // Fault tolerance: skip bad mandates instead of failing the job
                .faultTolerant()
                    .skip(TransactionValidationException.class)
                    .skip(IllegalStateException.class) // e.g. mandate in unexpected state
                    .skipLimit(SKIP_LIMIT)
                    .noRetry(TransactionValidationException.class) // validation won't fix itself on retry
                // Listener: logs step execution summary
                .listener(new StepExecutionListener() {
                    @Override
                    public void beforeStep(StepExecution stepExecution) {
                        log.info("SIP batch step starting | jobExecutionId={}",
                                stepExecution.getJobExecutionId());
                    }

                    @Override
                    public ExitStatus afterStep(StepExecution stepExecution) {
                        log.info("SIP batch step complete | read={} | processed={} | written={} | skipped={} | status={}",
                                stepExecution.getReadCount(),
                                stepExecution.getProcessSkipCount(),
                                stepExecution.getWriteCount(),
                                stepExecution.getSkipCount(),
                                stepExecution.getStatus());
                        return stepExecution.getExitStatus();
                    }
                })
                .build();
    }

    /**
     * The Job: contains the single processing step.
     *
     * In a more complex scenario you'd chain multiple steps:
     *   .start(validateMandatesStep())
     *   .next(processInstallmentsStep())
     *   .next(sendNotificationsStep())
     *
     * For our project, one step is sufficient and keeps the job simple.
     */
    @Bean
    public Job sipDailyJob() {
        return new JobBuilder("sipDailyJob", jobRepository)
                .start(sipProcessingStep())
                .listener(new JobExecutionListener() {
                    @Override
                    public void beforeJob(JobExecution jobExecution) {
                        log.info("SIP daily job starting | runDate={}",
                                jobExecution.getJobParameters().getString("runDate"));
                    }

                    @Override
                    public void afterJob(JobExecution jobExecution) {
                        log.info("SIP daily job finished | status={} | duration={}ms",
                                jobExecution.getStatus(),
                                jobExecution.getEndTime() != null && jobExecution.getStartTime() != null
                                    ? java.time.Duration.between(
                                            jobExecution.getStartTime(),
                                            jobExecution.getEndTime()).toMillis()
                                    : "N/A");

                        if (jobExecution.getStatus() == BatchStatus.FAILED) {
                            log.error("SIP daily job FAILED. Failures: {}",
                                    jobExecution.getAllFailureExceptions());
                        }
                    }
                })
                .build();
    }
}
