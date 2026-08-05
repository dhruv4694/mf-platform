package com.mfplatform.mfplatform.transaction.validation;

import com.mfplatform.mfplatform.transaction.TransactionContext;

/**
 * TransactionValidator is the Chain of Responsibility interface.
 *
 * Each implementation checks one specific condition. If the condition fails,
 * it throws TransactionValidationException immediately — stopping the chain.
 * If it passes, it returns normally — the chain continues to the next validator.
 *
 * IMPLEMENTATIONS:
 *   KycValidator                   — is the investor's KYC complete?
 *   FolioOwnershipValidator        — does this folio belong to the caller? (shared)
 *   SchemeOpenForPurchaseValidator — is the scheme accepting purchases?
 *   MinimumPurchaseAmountValidator — is the amount above the minimum?
 *   SchemeOpenForRedemptionValidator — is the scheme accepting redemptions?
 *   SufficientUnitsValidator       — does the investor hold enough units?
 *   LockInPeriodValidator          — has the lock-in period expired?
 *
 * WHY INTERFACE NOT ABSTRACT CLASS:
 * Validators are Spring @Component beans — they need to be independently
 * injectable (for testing and reuse across purchase/redemption chains).
 * An abstract class would couple them into a rigid hierarchy. The interface
 * lets each validator be independently tested by just calling validate() directly.
 */
public interface TransactionValidator {

    /**
     * Validates one aspect of the transaction context.
     *
     * @param context all data needed for validation (investor, folio, scheme,
     *                request details, actor identity)
     * @throws TransactionValidationException if this validator's condition fails
     */
    void validate(TransactionContext context);
}
