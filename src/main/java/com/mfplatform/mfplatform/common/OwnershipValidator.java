package com.mfplatform.mfplatform.common;

import com.mfplatform.mfplatform.folio.FolioRepository;
import com.mfplatform.mfplatform.investor.InvestorRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * OwnershipValidator centralises all "does actor X have access to entity Y"
 * checks across the entire application.
 *
 * WHY THIS EXISTS:
 * Before this class, the same ownership check logic was copy-pasted into:
 *   - FolioService (creating a folio for a distributor's client)
 *   - FolioSecurity (can this actor view/transact on this folio?)
 *   - InvestorSecurity (can this actor view this investor?)
 *   - DistributorSecurity (can this actor view this distributor?)
 *
 * Any bug in the ownership rule would need to be fixed in multiple places.
 * OwnershipValidator is the single source of truth — fix it here and every
 * caller gets the fix.
 *
 * TWO METHOD STYLES:
 *
 * 1. boolean isXxx(...) methods — return true/false.
 *    Used by Security beans (@PreAuthorize expressions) where Spring Security
 *    expects a boolean return and handles the 403 response itself.
 *
 * 2. void assertXxx(...) methods — throw AccessDeniedException if check fails.
 *    Used inside Services where you want the error to propagate naturally
 *    without writing "if (!check) throw ..." at every call site. Spring's
 *    GlobalExceptionHandler catches AccessDeniedException and returns a 403.
 *
 * DEPENDENCY NOTE:
 * This class sits in the `common` package and imports from `investor` and `folio`
 * packages. Those packages do NOT import from `common` (except Role enum), so
 * there are no circular dependencies.
 */
@Service
public class OwnershipValidator {

    private final InvestorRepository investorRepository;
    private final FolioRepository folioRepository;

    public OwnershipValidator(InvestorRepository investorRepository, FolioRepository folioRepository) {
        this.investorRepository = investorRepository;
        this.folioRepository = folioRepository;
    }

    // ─── Investor ownership ───────────────────────────────────────────────────

    /**
     * Is the given investor one of this distributor's clients?
     *
     * Checks: investor.distributorId == distributorId
     *
     * Returns false (not throws) if the investor doesn't exist, which is
     * the safe default — unknown entity = no access.
     */
    public boolean isDistributorClient(Long investorId, Long distributorId) {
        return investorRepository.findById(investorId)
                .map(inv -> distributorId.equals(inv.getDistributorId()))
                .orElse(false);
    }

    /**
     * Assert version of isDistributorClient() — throws AccessDeniedException
     * if the investor is NOT in this distributor's book.
     *
     * Used in FolioService.createFolio() where we want to stop execution
     * and return a 403 immediately if the check fails.
     */
    public void assertIsDistributorClient(Long investorId, Long distributorId) {
        if (!isDistributorClient(investorId, distributorId)) {
            throw new AccessDeniedException(
                    "Cannot perform this action for an investor outside your client book");
        }
    }

    // ─── Folio ownership ──────────────────────────────────────────────────────

    /**
     * Does this folio belong to this investor?
     *
     * Checks: folio.investorId == investorId
     */
    public boolean isInvestorFolio(Long folioId, Long investorId) {
        return folioRepository.findById(folioId)
                .map(folio -> investorId.equals(folio.getInvestorId()))
                .orElse(false);
    }

    /**
     * Does this folio belong to an investor in this distributor's book?
     *
     * Two-hop check: folio → investor → distributorId.
     * Reuses isDistributorClient() so the client-book rule is only defined once.
     */
    public boolean isDistributorFolio(Long folioId, Long distributorId) {
        return folioRepository.findById(folioId)
                .map(folio -> isDistributorClient(folio.getInvestorId(), distributorId))
                .orElse(false);
    }

    /**
     * Assert version combining both folio ownership checks with role routing.
     *
     * Used in TransactionService and SipMandateService where we need to verify
     * that a transaction or SIP is being created against a folio the caller
     * actually has access to — after the @PreAuthorize layer has already done
     * a first-pass check.
     */
    public void assertCanAccessFolio(Long folioId, Long investorId, Long distributorId, Role role) {
        boolean allowed = switch (role) {
            case ADMIN -> true;
            case INVESTOR -> isInvestorFolio(folioId, investorId);
            case DISTRIBUTOR -> isDistributorFolio(folioId, distributorId);
        };
        if (!allowed) {
            throw new AccessDeniedException("You do not have access to this folio");
        }
    }
}
