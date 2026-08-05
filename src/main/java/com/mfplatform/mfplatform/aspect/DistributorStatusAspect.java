package com.mfplatform.mfplatform.aspect;

import com.mfplatform.mfplatform.common.Role;
import com.mfplatform.mfplatform.distributor.DistributorService;
import com.mfplatform.mfplatform.security.ActorContext;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * DistributorStatusAspect enforces the @RequireActiveDistributor annotation.
 *
 * AOP CONCEPTS DEMONSTRATED:
 *
 * @Aspect — marks this class as an aspect (a modularization of a cross-cutting concern)
 *
 * @Before — advice type: runs BEFORE the intercepted method executes.
 *   Other advice types not used here:
 *     @After       — runs after (regardless of outcome)
 *     @AfterReturning — runs only on success
 *     @AfterThrowing  — runs only on exception
 *     @Around      — wraps the method (used in ExecutionTimeAspect)
 *
 * Pointcut expression: @annotation(RequireActiveDistributor)
 *   This matches any method annotated with @RequireActiveDistributor.
 *   The full qualified name is inferred from the same package.
 *
 * JoinPoint — represents the intercepted method call. We use it here to
 *   read context about where the advice fired (for logging), but we don't
 *   need to control execution flow since @Before can't do that. If we needed
 *   to conditionally skip the method, we'd use @Around instead.
 *
 * HOW IT INTEGRATES WITH SPRING SECURITY:
 * @PreAuthorize runs FIRST (Spring Security's AOP proxy has higher precedence).
 * By the time this aspect fires, we already know the caller is authenticated
 * and has DISTRIBUTOR or ADMIN role. We just need to check the distributor's
 * operational status — a business-level check, not a role-level check.
 *
 * EXECUTION ORDER:
 *   HTTP request
 *     → JwtAuthFilter (sets SecurityContext with ActorContext)
 *     → @PreAuthorize AOP proxy (checks role)
 *     → @RequireActiveDistributor AOP proxy (checks status) ← THIS ASPECT
 *     → controller method body
 */
@Aspect
@Component
public class DistributorStatusAspect {

    private final DistributorService distributorService;

    public DistributorStatusAspect(DistributorService distributorService) {
        this.distributorService = distributorService;
    }

    /**
     * Fires before any method annotated with @RequireActiveDistributor.
     *
     * Logic:
     *   1. Read the current Authentication from Spring's SecurityContext
     *      (populated by JwtAuthFilter on every request)
     *   2. Extract the ActorContext (our custom principal carrying role + ids)
     *   3. If the caller is a DISTRIBUTOR, call assertDistributorActive()
     *      which throws DistributorNotActiveException if not ACTIVE
     *   4. If the caller is ADMIN or INVESTOR, do nothing — pass through
     *
     * Note: we don't need the JoinPoint parameter for any logic here,
     * but Spring AOP requires at least this method signature. We include it
     * for documentation purposes (could log joinPoint.getSignature() for debugging).
     */
    @Before("@annotation(RequireActiveDistributor)")
    public void checkDistributorActive(JoinPoint joinPoint) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        // No authentication present — let @PreAuthorize handle this case
        if (auth == null || !(auth.getPrincipal() instanceof ActorContext actor)) {
            return;
        }

        // Only distributors are subject to the active status check.
        // ADMINs are always allowed (they created the platform).
        // INVESTORs are never blocked by distributor status
        // (though annotating INVESTOR-only endpoints with this would be unusual).
        if (actor.role() == Role.DISTRIBUTOR) {
            // assertDistributorActive() throws DistributorNotActiveException
            // (→ 403 via GlobalExceptionHandler) if status is not ACTIVE.
            distributorService.assertDistributorActive(actor.distributorId());
        }
    }
}
