package com.mfplatform.mfplatform.batch;

import com.mfplatform.mfplatform.sip.SipMandate;

/**
 * SipInstallmentResult is the output of SipItemProcessor.
 *
 * WHY A SEPARATE RESULT RECORD (not just returning SipMandate):
 * The processor does significant work: creates a transaction, simulates
 * payment, runs allotment. The writer only needs to know:
 *   1. The updated mandate (to save the advanced nextDueDate)
 *   2. Whether the installment succeeded or failed (for logging/audit)
 *   3. The transaction ID (for linking to the notification)
 *
 * Returning a result record keeps the processor's output explicit and
 * the writer thin — it just persists what the processor computed.
 *
 * @param mandate        the SipMandate with nextDueDate already advanced
 *                       by SipItemProcessor (ready to be saved by the writer)
 * @param mandateId      convenience field (mandate.getId()) for logging
 * @param transactionId  the MfTransaction created for this installment (nullable
 *                       if the processor failed before creating a transaction)
 * @param success        whether payment was simulated as successful
 * @param failureReason  non-null when success=false, explains what failed
 */
public record SipInstallmentResult(
        SipMandate mandate,
        Long mandateId,
        Long transactionId,
        boolean success,
        String failureReason
) {
    public static SipInstallmentResult succeeded(SipMandate mandate, Long transactionId) {
        return new SipInstallmentResult(mandate, mandate.getId(), transactionId, true, null);
    }

    public static SipInstallmentResult failed(SipMandate mandate, String reason) {
        return new SipInstallmentResult(mandate, mandate.getId(), null, false, reason);
    }
}
