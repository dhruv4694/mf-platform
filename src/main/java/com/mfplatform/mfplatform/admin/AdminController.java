package com.mfplatform.mfplatform.admin;

import com.mfplatform.mfplatform.admin.dto.AdminDtos.*;
import com.mfplatform.mfplatform.batch.SipBatchJobLauncher;
import com.mfplatform.mfplatform.common.BusinessDateService;
import com.mfplatform.mfplatform.transaction.EodProcessingService;
import com.mfplatform.mfplatform.transaction.EodProcessingService.EodSummary;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * AdminController exposes the platform's business-date and EOD controls.
 *
 * ACCESS CONTROL: every endpoint here is ADMIN only, same convention as
 * NavController.
 *
 * These endpoints call the exact same services a production cron/scheduler
 * would call — there is no scheduler wired up for EOD itself (unlike the SIP
 * batch job, which does run on a cron). Settlement is triggered explicitly by
 * an admin, which is the point: the business date is a virtual clock the
 * admin controls, not tied to the real one.
 */
@Tag(name = "Admin", description = "Business date control and End-of-Day settlement. Admin only.")
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final BusinessDateService businessDateService;
    private final EodProcessingService eodProcessingService;
    private final SipBatchJobLauncher sipBatchJobLauncher;

    public AdminController(
            BusinessDateService businessDateService,
            EodProcessingService eodProcessingService,
            SipBatchJobLauncher sipBatchJobLauncher) {
        this.businessDateService = businessDateService;
        this.eodProcessingService = eodProcessingService;
        this.sipBatchJobLauncher = sipBatchJobLauncher;
    }

    /**
     * Returns the platform's current business date.
     */
    @GetMapping("/business-date")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BusinessDateResponse> getBusinessDate() {
        return ResponseEntity.ok(new BusinessDateResponse(businessDateService.today()));
    }

    /**
     * Advances the business date. Must not be before the current business date.
     */
    @PutMapping("/business-date")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BusinessDateResponse> advanceBusinessDate(
            @Valid @RequestBody AdvanceBusinessDateRequest request) {
        businessDateService.advance(request.date());
        return ResponseEntity.ok(new BusinessDateResponse(businessDateService.today()));
    }

    /**
     * Runs EOD settlement for a business date — defaults to the current
     * business date if none is given. Settles every PENDING transaction
     * stamped with that date (payment → NAV → allotment); anything whose
     * scheme has no NAV published yet stays PENDING for a future rerun.
     */
    @PostMapping("/eod/run")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<EodSummaryResponse> runEod(@RequestBody(required = false) RunEodRequest request) {
        LocalDate businessDate = (request != null && request.businessDate() != null)
                ? request.businessDate()
                : businessDateService.today();

        EodSummary summary = eodProcessingService.runEod(businessDate);

        return ResponseEntity.ok(new EodSummaryResponse(
                businessDate,
                summary.processed(),
                summary.allotted(),
                summary.failed(),
                summary.pendingNoNav()
        ));
    }

    /**
     * Manually triggers the SIP daily batch job for the current business date.
     *
     * The SIP batch normally only fires via a real-wall-clock 9 AM cron
     * (SipBatchJobLauncher.scheduledRun()), which has no relationship to
     * BusinessDateService's virtual date — advancing the business date alone
     * never makes a SIP installment due. This endpoint calls the exact same
     * job-launch logic on demand, same idempotency spirit as Run EOD
     * (JobParameters include a timestamp so repeated manual runs on the same
     * business date are always valid new job instances).
     */
    @PostMapping("/sip/run-batch")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SipBatchRunResponse> runSipBatch() {
        JobExecution execution = sipBatchJobLauncher.runManually();
        StepExecution step = execution.getStepExecutions().stream().findFirst().orElse(null);

        return ResponseEntity.ok(new SipBatchRunResponse(
                execution.getStatus().toString(),
                execution.getExitStatus().getExitCode(),
                step != null ? step.getReadCount() : 0,
                step != null ? step.getWriteCount() : 0,
                step != null ? step.getSkipCount() : 0
        ));
    }
}
