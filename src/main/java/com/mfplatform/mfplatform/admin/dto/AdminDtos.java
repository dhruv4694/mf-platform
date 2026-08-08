package com.mfplatform.mfplatform.admin.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * DTOs for the admin module — business date control and EOD triggering.
 */
public class AdminDtos {

    public record BusinessDateResponse(LocalDate businessDate) {}

    public record AdvanceBusinessDateRequest(@NotNull LocalDate date) {}

    /**
     * businessDate is optional — null means "settle today's business date"
     * (BusinessDateService.today()).
     */
    public record RunEodRequest(LocalDate businessDate) {}

    public record EodSummaryResponse(
            LocalDate businessDate,
            int processed,
            int allotted,
            int failed,
            int pendingNoNav
    ) {}

    /**
     * Result of a manually-triggered SIP batch run. read/written/skipped come
     * from the job's single step execution (SipBatchJobConfig.sipProcessingStep).
     */
    public record SipBatchRunResponse(
            String status,
            String exitCode,
            long read,
            long written,
            long skipped
    ) {}
}
