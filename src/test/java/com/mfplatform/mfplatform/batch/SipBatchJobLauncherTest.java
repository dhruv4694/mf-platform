package com.mfplatform.mfplatform.batch;

import com.mfplatform.mfplatform.common.BusinessDateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.launch.JobLauncher;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SipBatchJobLauncher.
 *
 * WHAT WE'RE TESTING:
 *   1. scheduledRun() (the @Scheduled cron path) and runManually() (the
 *      AdminController manual-trigger path) both ultimately launch the same
 *      job via the same JobParameters-building logic — verified by asserting
 *      both paths call jobLauncher.run(sipDailyJob, params) with a "runDate"
 *      parameter equal to BusinessDateService.today().
 *   2. runManually() propagates a launch failure (AdminController should see
 *      a real error); scheduledRun() swallows it (a failed cron run must not
 *      crash the scheduler).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SipBatchJobLauncher")
class SipBatchJobLauncherTest {

    @Mock private JobLauncher jobLauncher;
    @Mock private Job sipDailyJob;
    @Mock private BusinessDateService businessDateService;
    @Mock private JobExecution jobExecution;

    @InjectMocks
    private SipBatchJobLauncher sipBatchJobLauncher;

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 3, 15);

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(businessDateService.today()).thenReturn(BUSINESS_DATE);
        lenient().when(jobLauncher.run(eq(sipDailyJob), any(JobParameters.class))).thenReturn(jobExecution);
        lenient().when(jobExecution.getStatus()).thenReturn(BatchStatus.COMPLETED);
        lenient().when(jobExecution.getExitStatus()).thenReturn(ExitStatus.COMPLETED);
    }

    @Test
    @DisplayName("runManually() launches the job with runDate = current business date")
    void runManuallyLaunchesJobWithBusinessDate() throws Exception {
        JobExecution result = sipBatchJobLauncher.runManually();

        assertThat(result).isSameAs(jobExecution);

        ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobLauncher).run(eq(sipDailyJob), captor.capture());
        assertThat(captor.getValue().getString("runDate")).isEqualTo(BUSINESS_DATE.toString());
    }

    @Test
    @DisplayName("scheduledRun() launches the job through the exact same path as runManually()")
    void scheduledRunUsesSameLaunchLogicAsRunManually() throws Exception {
        sipBatchJobLauncher.scheduledRun();

        ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobLauncher).run(eq(sipDailyJob), captor.capture());
        assertThat(captor.getValue().getString("runDate")).isEqualTo(BUSINESS_DATE.toString());
    }

    @Test
    @DisplayName("runManually() propagates a launch failure to the caller")
    void runManuallyPropagatesFailure() throws Exception {
        when(jobLauncher.run(eq(sipDailyJob), any(JobParameters.class)))
                .thenThrow(new RuntimeException("simulated launch failure"));

        assertThatThrownBy(() -> sipBatchJobLauncher.runManually())
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("scheduledRun() swallows a launch failure — must not crash the scheduler")
    void scheduledRunSwallowsFailure() throws Exception {
        when(jobLauncher.run(eq(sipDailyJob), any(JobParameters.class)))
                .thenThrow(new RuntimeException("simulated launch failure"));

        assertThatCode(() -> sipBatchJobLauncher.scheduledRun()).doesNotThrowAnyException();
    }
}
