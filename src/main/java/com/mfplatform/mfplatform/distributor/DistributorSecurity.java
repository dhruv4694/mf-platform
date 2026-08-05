package com.mfplatform.mfplatform.distributor;

import com.mfplatform.mfplatform.common.OwnershipValidator;
import com.mfplatform.mfplatform.security.ActorContext;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * DistributorSecurity is used exclusively in @PreAuthorize expressions on
 * DistributorController, following the exact same pattern as InvestorSecurity.
 *
 * Having a dedicated Security bean for each module (rather than inline SpEL
 * expressions) means:
 *   - The logic is unit-testable (just call canView() directly in a test)
 *   - The @PreAuthorize annotation stays readable ("@distributorSecurity.canView(...)")
 *   - The ownership rule lives in OwnershipValidator, not scattered across
 *     multiple SpEL strings that would all need updating if the rule changed
 *
 * Note: distributors don't have the same cross-entity ownership complexity
 * as investors (a distributor doesn't "own" another distributor), so the
 * DISTRIBUTOR case here is just a simple id equality check rather than a
 * DB lookup like the DISTRIBUTOR→investor case in InvestorSecurity.
 */
@Component("distributorSecurity")
public class DistributorSecurity {

    // OwnershipValidator injected for consistency and future-proofing,
    // though for distributors the check is simple enough that we could
    // do it inline — keeping the pattern uniform across all Security beans
    // is worth more than the slight over-engineering here.
    private final OwnershipValidator ownershipValidator;

    public DistributorSecurity(OwnershipValidator ownershipValidator) {
        this.ownershipValidator = ownershipValidator;
    }

    /**
     * Can this authenticated user view distributor record with the given id?
     *
     * ADMIN       → yes, always
     * DISTRIBUTOR → only if the id matches their own distributorId from the JWT
     * INVESTOR    → never (investors have no reason to view distributor records directly)
     */
    public boolean canView(Long distributorId, Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof ActorContext actor)) {
            return false;
        }

        return switch (actor.role()) {
            case ADMIN -> true;
            case DISTRIBUTOR -> distributorId.equals(actor.distributorId());
            case INVESTOR -> false;
        };
    }
}
