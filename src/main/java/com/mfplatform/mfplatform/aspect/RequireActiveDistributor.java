package com.mfplatform.mfplatform.aspect;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @RequireActiveDistributor is a custom annotation that enforces distributor
 * status checks declaratively — without scattering manual
 * assertDistributorActive() calls across every controller method.
 *
 * HOW IT WORKS:
 * Any method annotated with @RequireActiveDistributor is intercepted by
 * DistributorStatusAspect BEFORE the method body runs. The aspect reads
 * the current caller's role from the SecurityContext. If the caller is a
 * DISTRIBUTOR and their status is not ACTIVE, AccessDeniedException is
 * thrown — the method never executes.
 *
 * ADMIN callers pass through unconditionally (admins are never blocked by
 * distributor status). INVESTOR callers are also ignored (this annotation
 * only makes sense on endpoints reachable by DISTRIBUTOR role).
 *
 * BEFORE THIS ANNOTATION existed, we had:
 *   InvestorController.addInvestor() {
 *     ActorContext actor = resolveActor(authentication);
 *     if (actor.role() == Role.DISTRIBUTOR) {
 *         distributorService.assertDistributorActive(actor.distributorId());
 *     }
 *     ...
 *   }
 *
 * This manual check would need to be copied into EVERY method that a
 * distributor can call: addInvestor, createFolio, transactions, SIPs.
 * That's a cross-cutting concern — the same logic repeated across unrelated classes.
 *
 * AFTER: just annotate the method:
 *   @PostMapping
 *   @PreAuthorize("hasAnyRole('ADMIN','DISTRIBUTOR')")
 *   @RequireActiveDistributor
 *   public ResponseEntity<InvestorResponse> addInvestor(...) { ... }
 *
 * PLACEMENT:
 * Can be placed on:
 *   - Controller methods (most common)
 *   - Service methods (if you want the check deeper in the stack)
 *
 * For this project we place it on controller methods, alongside @PreAuthorize.
 * This keeps all authorization concerns visible at the HTTP entry point.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireActiveDistributor {
}
