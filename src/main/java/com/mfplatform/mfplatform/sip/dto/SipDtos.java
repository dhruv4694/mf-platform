package com.mfplatform.mfplatform.sip.dto;

import com.mfplatform.mfplatform.sip.SipFrequency;
import jakarta.validation.constraints.DecimalMin;
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
     */
    public record RegisterSipRequest(
            @NotNull Long folioId,
            @NotNull Long schemeId,
            @NotNull @DecimalMin(value = "500.00", message = "SIP amount must be at least ₹500")
            BigDecimal amount,
            @NotNull SipFrequency frequency,
            @NotNull LocalDate startDate,
            LocalDate endDate   // nullable — open-ended if null
    ) {}

    /**
     * Response for a SIP mandate — includes current status and
     * next installment date so investors can track their schedule.
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
            Instant createdAt
    ) {}
}
