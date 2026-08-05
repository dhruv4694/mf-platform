package com.mfplatform.mfplatform.batch;

import com.mfplatform.mfplatform.common.BusinessDateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.*;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * SipBatchJobLauncher triggers the SIP daily batch job via @Scheduled.
 *
 * WHY SEPARATE LAUNCHER (not @Scheduled directly on a job method):
 * Spring Batch jobs need JobParameters to be unique per execution.
 * The launcher constructs these parameters (including today's date) before
 * launching — keeping the scheduling concern separate from the job definition.
 *
 * HOW @Scheduled + JobLauncher REPLACES THE OLD SipExecutionService:
 *
 * OLD (SipExecutionService):
 *   @Scheduled(cron = "0 0 9 * * *")
 *   public void processDueSips() {
 *       // manually loop through all mandates
 *       // no restart if it crashes
 *       // all-or-nothing — one failure stops processing
 *   }
 *
 * NEW (SipBatchJobLauncher):
 *   @Scheduled(cron = "0 0 9 * * *")
 *   public void runSipJob() {
 *       jobLauncher.run(sipDailyJob, params); // delegates to Spring Batch
 *       // restart-capable (resumes from last committed chunk)
 *       // fault-tolerant (skips bad mandates, continues)
 *       // observable (BATCH_* tables record every execution)
 *   }
 *
 * JOB PARAMETERS:
 * Spring Batch identifies a job instance by its name + parameters.
 * Using today's date as a parameter means each day's run is a unique
 * job instance. Without this, Spring Batch would think today's run is
 * a re-run of yesterday's (same job name, no distinguishing parameters)
 * and refuse to launch it (JobInstanceAlreadyCompleteException).
 *
 * ASYNC JOB LAUNCHER:
 * We use the standard synchronous JobLauncher here — the @Scheduled method
 * blocks until the job completes. For very large datasets you'd switch to
 * AsyncJobLauncher (Spring Batch provides one) so the scheduler thread
 * returns immediately and the job runs on a separate thread.
 * For our project's scale, synchronous is appropriate and simpler.
 */
@Component
public class SipBatchJobLauncher {

    private static final Logger log = LoggerFactory.getLogger(SipBatchJobLauncher.class);

    private final JobLauncher jobLauncher;
    private final Job sipDailyJob;
    private final BusinessDateService businessDateService;

    public SipBatchJobLauncher(JobLauncher jobLauncher, Job sipDailyJob, BusinessDateService businessDateService) {
        this.jobLauncher = jobLauncher;
        this.sipDailyJob = sipDailyJob;
        this.businessDateService = businessDateService;
    }

    /**
     * Launches the SIP daily batch job every morning at 9:00 AM.
     *
     * This replaces the @Scheduled method in SipExecutionService.
     * The business logic (what to do for each mandate) moved to:
     *   SipItemReader    → which mandates
     *   SipItemProcessor → what to do per mandate
     *   SipItemWriter    → how to persist the result
     *
     * runDate parameter: today's date as a string.
     * Ensures a unique BATCH_JOB_INSTANCE per day.
     */
    @Scheduled(cron = "0 0 9 * * *")
    public void runSipJob() {
        String runDate = businessDateService.today().toString();
        log.info("Triggering SIP daily batch job for runDate={}", runDate);

        try {
            JobParameters params = new JobParametersBuilder()
                    .addString("runDate", runDate)
                    // Adding current timestamp ensures uniqueness even if the job
                    // is manually re-triggered on the same day (for testing)
                    .addLong("triggeredAt", System.currentTimeMillis())
                    .toJobParameters();

            JobExecution execution = jobLauncher.run(sipDailyJob, params);

            log.info("SIP batch job completed | status={} | exitCode={}",
                    execution.getStatus(),
                    execution.getExitStatus().getExitCode());

        } catch (Exception ex) {
            // Log but don't rethrow — a failed job launch shouldn't crash the scheduler
            // The failure is already recorded in BATCH_JOB_EXECUTION by Spring Batch
            log.error("Failed to launch SIP batch job for {}: {}", runDate, ex.getMessage(), ex);
        }
    }
}
