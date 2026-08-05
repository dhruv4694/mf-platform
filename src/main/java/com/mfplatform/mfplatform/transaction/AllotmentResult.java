package com.mfplatform.mfplatform.transaction;

import java.math.BigDecimal;

/**
 * AllotmentResult models the outcome of a unit allotment attempt.
 *
 * SEALED INTERFACE + PATTERN MATCHING (Java 17):
 * A sealed interface restricts which classes can implement it — only the
 * three permitted subtypes below. Combined with a switch expression, the
 * compiler enforces exhaustive handling: every caller MUST handle all three
 * outcomes or it won't compile.
 *
 * This is safer than an enum with a result field, or throwing exceptions for
 * all non-success cases, because:
 *   - Success, Failed, and Pending are structurally different (different data)
 *   - Callers can't ignore the Pending or Failed cases
 *   - Adding a new outcome type makes every switch a compile error until handled
 *
 * WHEN EACH OUTCOME OCCURS:
 *   Success — worker claimed the transaction and holding was updated
 *   Failed  — allotment failed (insufficient units, holding lock failed after max retries)
 *   Pending — worker did not claim the transaction (another worker owns it)
 *             this is NOT an error — it's the expected "someone else got it" case
 *
 * USAGE (Java 17 compatible — instanceof pattern matching):
 *   AllotmentResult result = unitAllotmentService.allot(transactionId);
 *   if (result instanceof AllotmentResult.Success s) {
 *       log.info("Allotted {} units", s.allottedUnits());
 *   } else if (result instanceof AllotmentResult.Failed f) {
 *       notifyFailure(f.reason());
 *   } else if (result instanceof AllotmentResult.Pending p) {
 *       log.debug("Skipped: {}", p.reason());
 *   }
 */
public sealed interface AllotmentResult
        permits AllotmentResult.Success,
                AllotmentResult.Failed,
                AllotmentResult.Pending {

    /**
     * Allotment completed successfully.
     * The holding has been updated and the transaction is ALLOTTED.
     *
     * @param allottedUnits the units added (purchase) or subtracted (redemption)
     * @param applicableNavValue the NAV value used for the calculation
     */
    record Success(
            BigDecimal allottedUnits,
            BigDecimal applicableNavValue
    ) implements AllotmentResult {}

    /**
     * Allotment failed due to a business or technical error.
     * The transaction has been marked FAILED.
     * The holding was NOT updated.
     *
     * @param reason human-readable explanation for logging/notification
     */
    record Failed(String reason) implements AllotmentResult {}

    /**
     * This worker did not claim the transaction — another worker owns it.
     * The transaction has NOT been marked failed. It will be processed by
     * the worker that won the claim.
     *
     * This is NOT an error. It's the expected outcome for the losing worker
     * in a multi-instance deployment.
     *
     * @param reason explanation for debugging (e.g. "claimed = 0")
     */
    record Pending(String reason) implements AllotmentResult {}
}
