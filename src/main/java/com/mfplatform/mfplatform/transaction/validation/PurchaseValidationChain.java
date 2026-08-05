package com.mfplatform.mfplatform.transaction.validation;

import com.mfplatform.mfplatform.transaction.TransactionContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Assembles and runs the validation chain for PURCHASE transactions.
 *
 * ORDER MATTERS — validators run in the order declared here:
 *   1. KycValidator              — in-memory, cheapest — fail fast
 *   2. FolioOwnershipValidator   — one DB lookup (OwnershipValidator)
 *   3. SchemeOpenForPurchaseValidator — in-memory (Scheme already in context)
 *   4. MinimumPurchaseAmountValidator — in-memory (Scheme already in context)
 *
 * General principle: cheapest checks first, DB-heavy calls last.
 * If KYC fails, we don't waste a DB query on ownership.
 *
 * Adding a new purchase validation:
 *   1. Create a new class implementing TransactionValidator in Validators.java
 *   2. Add it as a constructor parameter here
 *   3. Add it to List.of(...) at the right position
 *   Zero changes to existing validators or PurchaseService.
 */
@Component
public class PurchaseValidationChain {

    private final List<TransactionValidator> validators;

    public PurchaseValidationChain(
            KycValidator kycValidator,
            FolioOwnershipValidator folioOwnershipValidator,
            SchemeOpenForPurchaseValidator schemeOpenValidator,
            MinimumPurchaseAmountValidator minimumAmountValidator) {

        this.validators = List.of(
                kycValidator,
                folioOwnershipValidator,
                schemeOpenValidator,
                minimumAmountValidator
        );
    }

    /**
     * Runs all purchase validators in sequence.
     * Stops at the first failure — throws TransactionValidationException.
     */
    public void validate(TransactionContext context) {
        validators.forEach(v -> v.validate(context));
    }
}
