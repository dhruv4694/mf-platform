package com.mfplatform.mfplatform.transaction.validation;

import com.mfplatform.mfplatform.transaction.TransactionContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Assembles and runs the validation chain for REDEMPTION transactions.
 *
 * ORDER:
 *   1. KycValidator                    — in-memory, cheapest
 *   2. FolioOwnershipValidator         — one DB lookup
 *   3. SchemeOpenForRedemptionValidator — in-memory
 *   4. SufficientUnitsValidator        — in-memory (currentUnitsHeld in context)
 *   5. LockInPeriodValidator           — in-memory (scheme data in context)
 *
 * KycValidator and FolioOwnershipValidator are shared with PurchaseValidationChain.
 * SufficientUnitsValidator, LockInPeriodValidator, SchemeOpenForRedemptionValidator
 * are redemption-specific.
 */
@Component
public class RedemptionValidationChain {

    private final List<TransactionValidator> validators;

    public RedemptionValidationChain(
            KycValidator kycValidator,
            FolioOwnershipValidator folioOwnershipValidator,
            SchemeOpenForRedemptionValidator schemeOpenValidator,
            SufficientUnitsValidator sufficientUnitsValidator,
            LockInPeriodValidator lockInPeriodValidator) {

        this.validators = List.of(
                kycValidator,
                folioOwnershipValidator,
                schemeOpenValidator,
                sufficientUnitsValidator,
                lockInPeriodValidator
        );
    }

    /**
     * Runs all redemption validators in sequence.
     * Stops at the first failure — throws TransactionValidationException.
     */
    public void validate(TransactionContext context) {
        validators.forEach(v -> v.validate(context));
    }
}
