package com.mfplatform.mfplatform.investor;

import com.mfplatform.mfplatform.common.OwnershipValidator;
import com.mfplatform.mfplatform.common.Role;
import com.mfplatform.mfplatform.security.ActorContext;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * InvestorSecurity is a Spring-managed bean used exclusively in @PreAuthorize
 * expressions on InvestorController endpoints.
 *
 * Spring Security evaluates @PreAuthorize("@investorSecurity.canView(#id, authentication)")
 * BEFORE the controller method body runs. If canView() returns false, Spring
 * returns a 403 automatically — the controller method is never called.
 *
 * Previously, the ownership check logic lived directly in this class. Now it
 * delegates to OwnershipValidator, which is the single source of truth for
 * "does this actor have access to this entity." This class just routes the
 * question to the right OwnershipValidator method based on the actor's role.
 */
@Component("investorSecurity")
public class InvestorSecurity {

    private final OwnershipValidator ownershipValidator;

    public InvestorSecurity(OwnershipValidator ownershipValidator) {
        this.ownershipValidator = ownershipValidator;
    }

    /**
     * Can this authenticated user view investor record with the given id?
     *
     * ADMIN      → yes, always
     * INVESTOR   → only if the id matches their own investorId from the JWT
     * DISTRIBUTOR → only if that investor is in their client book
     *               (i.e. investor.distributorId == actor.distributorId)
     */
    public boolean canView(Long investorId, Authentication authentication) {
        // Extract the resolved caller identity from the Authentication principal.
        // ActorContext is set by JwtAuthFilter — it carries userId, role,
        // investorId, and distributorId parsed directly from the JWT claims.
        if (!(authentication.getPrincipal() instanceof ActorContext actor)) {
            return false; // no valid principal — shouldn't reach here due to SecurityConfig
        }

        return switch (actor.role()) {
            case ADMIN -> true;
            case INVESTOR -> investorId.equals(actor.investorId());
            case DISTRIBUTOR -> ownershipValidator.isDistributorClient(investorId, actor.distributorId());
        };
    }
}
