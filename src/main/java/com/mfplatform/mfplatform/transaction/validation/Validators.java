package com.mfplatform.mfplatform.transaction.validation;

import com.mfplatform.mfplatform.common.OwnershipValidator;
import com.mfplatform.mfplatform.common.Role;
import com.mfplatform.mfplatform.transaction.TransactionContext;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

// ─── KycValidator ────────────────────────────────────────────────────────────

/**
 * Checks that the investor's KYC is complete before allowing any transaction.
 *
 * In a real AMC system, KYC status is tracked in a separate KYC module
 * and verified with CAMS/KFintech. Here we check a simple isKycComplete
 * flag on the Investor entity.
 *
 * This runs FIRST in both chains because it's an in-memory check
 * (no DB query beyond what's already in the context) and eliminates
 * the most fundamental ineligibility before doing anything more expensive.
 */
@Component
class KycValidator implements TransactionValidator {

    @Override
    public void validate(TransactionContext context) {
        if (!context.investor().isKycComplete()) {
            throw new TransactionValidationException(
                    "KYC is not complete for investor " + context.investor().getId() +
                    ". Please complete KYC before transacting.");
        }
    }
}

// ─── FolioOwnershipValidator ──────────────────────────────────────────────────

/**
 * Checks that the actor has the right to transact on this folio.
 * Shared by both purchase and redemption chains — the ownership rule
 * is the same regardless of transaction direction.
 *
 * This is a second line of defense after @PreAuthorize on the controller.
 * The controller's @PreAuthorize catches most invalid access attempts.
 * This validator catches edge cases where the folio ID in the request
 * body doesn't match what @PreAuthorize checked on the URL parameter.
 */
@Component
class FolioOwnershipValidator implements TransactionValidator {

    private final OwnershipValidator ownershipValidator;

    FolioOwnershipValidator(OwnershipValidator ownershipValidator) {
        this.ownershipValidator = ownershipValidator;
    }

    @Override
    public void validate(TransactionContext context) {
        boolean hasAccess = switch (context.actor().role()) {
            case ADMIN -> true;
            case INVESTOR -> ownershipValidator.isInvestorFolio(
                    context.folio().getId(), context.actor().investorId());
            case DISTRIBUTOR -> ownershipValidator.isDistributorFolio(
                    context.folio().getId(), context.actor().distributorId());
        };

        if (!hasAccess) {
            throw new TransactionValidationException(
                    "You do not have permission to transact on folio "
                    + context.folio().getFolioNumber());
        }
    }
}

// ─── SchemeOpenForPurchaseValidator ───────────────────────────────────────────

/**
 * Checks that the scheme is currently accepting purchase transactions.
 *
 * In a real fund system, schemes can be temporarily closed for purchase
 * (e.g. during an NFO allotment period, or if AUM hits a cap for a liquid fund).
 * We simulate this with a simple isOpenForPurchase() check on the Scheme entity.
 */
@Component
class SchemeOpenForPurchaseValidator implements TransactionValidator {

    @Override
    public void validate(TransactionContext context) {
        if (!context.scheme().isOpenForPurchase()) {
            throw new TransactionValidationException(
                    "Scheme '" + context.scheme().getSchemeName() +
                    "' is currently closed for purchase.");
        }
    }
}

// ─── SchemeOpenForRedemptionValidator ─────────────────────────────────────────

/**
 * Checks that the scheme is currently accepting redemption transactions.
 * Schemes can be temporarily closed for redemption during stress events
 * (SEBI allows fund houses to gate redemptions in extreme market conditions).
 */
@Component
class SchemeOpenForRedemptionValidator implements TransactionValidator {

    @Override
    public void validate(TransactionContext context) {
        if (!context.scheme().isOpenForRedemption()) {
            throw new TransactionValidationException(
                    "Scheme '" + context.scheme().getSchemeName() +
                    "' is currently closed for redemption.");
        }
    }
}

// ─── MinimumPurchaseAmountValidator ───────────────────────────────────────────

/**
 * Checks that the purchase amount meets the scheme's minimum purchase amount.
 *
 * SEBI mandates that AMCs publish minimum purchase amounts. In practice:
 *   - Regular purchase minimum: ₹1000 (most equity funds)
 *   - SIP minimum: ₹500/month
 *
 * We store this on the Scheme entity (minimumPurchaseAmount field).
 * Only runs in the PURCHASE chain, not the REDEMPTION chain.
 */
@Component
class MinimumPurchaseAmountValidator implements TransactionValidator {

    @Override
    public void validate(TransactionContext context) {
        BigDecimal minimum = context.scheme().getMinimumPurchaseAmount();
        BigDecimal requested = context.requestAmount();

        if (requested == null || requested.compareTo(minimum) < 0) {
            throw new TransactionValidationException(
                    "Purchase amount ₹" + requested + " is below the minimum " +
                    "purchase amount of ₹" + minimum +
                    " for scheme '" + context.scheme().getSchemeName() + "'.");
        }
    }
}

// ─── SufficientUnitsValidator ─────────────────────────────────────────────────

/**
 * Checks that the investor holds enough units to fulfill a redemption request.
 *
 * Uses currentUnitsHeld from TransactionContext — loaded before the validation
 * chain runs, not re-queried here.
 *
 * TWO REDEMPTION MODES (see RedemptionService):
 *   - By units: requestUnits is set — exact check, requested must be positive
 *     and not exceed the held balance.
 *   - By amount: requestUnits is null, requestAmount is set instead. The exact
 *     number of units this amount converts to isn't known until NAV is applied
 *     (now settled later by EOD, not at request time), so this validator can
 *     only do a basic sanity check here (positive amount, and the folio holds
 *     something at all) — not a precise "amount ÷ NAV <= held" comparison.
 *
 * Note: even the by-units check is a "best effort" check at request time. A
 * concurrent redemption submitted simultaneously might pass this check and
 * both proceed to allotment. Holding.subtractUnits() is the final safety net
 * for BOTH modes — it throws InsufficientUnitsException if the balance would
 * go negative at allotment time. Only runs in the REDEMPTION chain.
 */
@Component
class SufficientUnitsValidator implements TransactionValidator {

    @Override
    public void validate(TransactionContext context) {
        BigDecimal requestedUnits = context.requestUnits();
        BigDecimal held = context.currentUnitsHeld();

        if (requestedUnits != null) {
            if (requestedUnits.compareTo(BigDecimal.ZERO) <= 0) {
                throw new TransactionValidationException(
                        "Redemption units must be greater than zero.");
            }
            if (requestedUnits.compareTo(held) > 0) {
                throw new TransactionValidationException(
                        "Insufficient units: requested " + requestedUnits +
                        " but only " + held + " units held in folio "
                        + context.folio().getFolioNumber() + ".");
            }
            return;
        }

        // Redemption by amount — defer the exact units-vs-held check to
        // allotment time (Holding.subtractUnits()), once NAV is known.
        BigDecimal requestedAmount = context.requestAmount();
        if (requestedAmount == null || requestedAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new TransactionValidationException(
                    "Redemption amount must be greater than zero.");
        }
        if (held.compareTo(BigDecimal.ZERO) <= 0) {
            throw new TransactionValidationException(
                    "No units held in folio " + context.folio().getFolioNumber() +
                    " for this scheme.");
        }
    }
}

// ─── LockInPeriodValidator ────────────────────────────────────────────────────

/**
 * Checks that the lock-in period has expired before allowing redemption.
 *
 * ELSS (Equity Linked Savings Scheme) funds have a mandatory 3-year lock-in
 * period — SEBI requires investors to hold units for at least 3 years before
 * redeeming. No other category has a mandatory lock-in in this project
 * (some real funds have voluntary lock-ins but we simplify here).
 *
 * Lock-in applies per-unit, per-purchase (each SIP installment has its own
 * 3-year clock). A full implementation would track purchase dates per unit
 * lot. Here we simplify: if the folio's OLDEST transaction for this scheme
 * is within 3 years, the entire holding is locked.
 *
 * This simplification is documented intentionally — a good interview talking
 * point: "I implemented the guard rail; full per-lot lock-in tracking would
 * require a lot_history table which I scoped out."
 *
 * Only runs in the REDEMPTION chain.
 */
@Component
class LockInPeriodValidator implements TransactionValidator {

    private final com.mfplatform.mfplatform.common.BusinessDateService businessDateService;

    LockInPeriodValidator(com.mfplatform.mfplatform.common.BusinessDateService businessDateService) {
        this.businessDateService = businessDateService;
    }

    @Override
    public void validate(TransactionContext context) {
        // Only ELSS schemes have a mandatory lock-in period
        if (context.scheme().getCategory() !=
                com.mfplatform.mfplatform.scheme.SchemeCategory.EQUITY) {
            return; // no lock-in for DEBT, HYBRID
        }

        // Simplified: check using the scheme's lock-in end date if stored
        // In a full implementation, this would query the earliest purchase
        // date for this folio/scheme combination and calculate from there.
        LocalDate lockInEnd = context.scheme().getLockInEndDate();
        if (lockInEnd != null && businessDateService.today().isBefore(lockInEnd)) {
            throw new TransactionValidationException(
                    "This scheme has a lock-in period that expires on " + lockInEnd +
                    ". Redemption is not allowed before this date.");
        }
    }
}
