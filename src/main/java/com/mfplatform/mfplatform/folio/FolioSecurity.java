package com.mfplatform.mfplatform.folio;

import com.mfplatform.mfplatform.common.OwnershipValidator;
import com.mfplatform.mfplatform.common.Role;
import com.mfplatform.mfplatform.security.ActorContext;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * FolioSecurity backs two @PreAuthorize expressions used on FolioController
 * and TransactionController:
 *
 *   @PreAuthorize("@folioSecurity.canView(#folioId, authentication)")
 *   @PreAuthorize("@folioSecurity.canTransact(#request.folioId(), authentication)")
 *
 * Both methods apply the same ownership rule — who can access a folio is the
 * same whether they're reading it or placing a transaction against it.
 * The two methods exist for readability at the call site, not because the logic
 * differs.
 *
 * The actual ownership check is fully delegated to OwnershipValidator.
 * This class purely routes to the right validator method based on actor.role().
 */
@Component("folioSecurity")
public class FolioSecurity {

    private final OwnershipValidator ownershipValidator;

    public FolioSecurity(OwnershipValidator ownershipValidator) {
        this.ownershipValidator = ownershipValidator;
    }

    /**
     * Can this actor view the given folio?
     * Delegates to checkAccess() — exists as a named method for readable @PreAuthorize.
     */
    public boolean canView(Long folioId, Authentication authentication) {
        return checkAccess(folioId, authentication);
    }

    /**
     * Can this actor place a transaction against the given folio?
     * Applies the same rule as canView() — if you can see a folio, you can
     * transact against it. These are split into separate methods so the
     * @PreAuthorize annotation on the transaction endpoint reads naturally.
     */
    public boolean canTransact(Long folioId, Authentication authentication) {
        return checkAccess(folioId, authentication);
    }

    /**
     * Core ownership check:
     *   ADMIN       → access to any folio
     *   INVESTOR    → only their own folios (folio.investorId == actor.investorId)
     *   DISTRIBUTOR → only folios belonging to investors in their client book
     *                 (folio.investor.distributorId == actor.distributorId)
     */
    private boolean checkAccess(Long folioId, Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof ActorContext actor)) {
            return false;
        }

        return switch (actor.role()) {
            case ADMIN -> true;
            case INVESTOR -> ownershipValidator.isInvestorFolio(folioId, actor.investorId());
            case DISTRIBUTOR -> ownershipValidator.isDistributorFolio(folioId, actor.distributorId());
        };
    }
}
