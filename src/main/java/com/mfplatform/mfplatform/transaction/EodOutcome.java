package com.mfplatform.mfplatform.transaction;

/**
 * The outcome of settling one transaction during an EOD run.
 * See EodTransactionProcessor.processOne() and EodProcessingService.runEod().
 */
public enum EodOutcome {
    ALLOTTED,
    FAILED,
    PENDING_NO_NAV
}
