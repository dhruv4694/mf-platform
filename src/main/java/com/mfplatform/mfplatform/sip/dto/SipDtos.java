package com.mfplatform.mfplatform.sip.dto;

import com.mfplatform.mfplatform.sip.SipFrequency;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public class SipDtos {

    /**
     * Request to register a new SIP mandate.
     *
     * endDate is optional — null means open-ended SIP.
     * startDate must be today or in the future.
     *
     * sipDay is required when frequency is MONTHLY (the recurring day-of-month
     * deductions land on, independent of startDate) and must be null/ignored
     * for WEEKLY/QUARTERLY — those use fixed calendar anchors instead, with no
     * user-chosen day. SipMandateService enforces this server-side (the @Min/@Max
     * here only bound the value when present; the "required for MONTHLY only"
     * rule is cross-field and validated in the service, same as the existing
     * units/amount XOR check for redemptions).
     */
    public record RegisterSipRequest(
            @NotNull Long folioId,
            @NotNull Long schemeId,
            @NotNull @DecimalMin(value = "500.00", message = "SIP amount must be at least ₹500")
            BigDecimal amount,
            @NotNull SipFrequency frequency,
            @NotNull LocalDate startDate,
            LocalDate endDate,   // nullable — open-ended if null
            @Min(value = 1, message = "sipDay must be between 1 and 31")
            @Max(value = 31, message = "sipDay must be between 1 and 31")
            Integer sipDay       // required for MONTHLY only — see class javadoc
    ) {}

    /**
     * Response for a SIP mandate — includes current status and
     * next installment date so investors can track their schedule.
     *
     * scheduleDescription is a backend-computed, human-readable summary of
     * the recurring schedule (e.g. "Deducted on the 5th of each month") so
     * the frontend never needs frequency-specific display logic of its own —
     * see SipMandateService.buildScheduleDescription().
     */
    public record SipMandateResponse(
            Long id,
            Long folioId,
            Long schemeId,
            BigDecimal amount,
            String frequency,
            LocalDate startDate,
            LocalDate endDate,
            LocalDate nextDueDate,
            String status,
            String mandateReference,
            Instant createdAt,
            String scheduleDescription
    ) {}
}
