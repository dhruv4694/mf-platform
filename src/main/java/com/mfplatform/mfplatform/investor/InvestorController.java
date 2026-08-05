package com.mfplatform.mfplatform.investor;

import com.mfplatform.mfplatform.aspect.RequireActiveDistributor;
import com.mfplatform.mfplatform.auth.AuthService;
import com.mfplatform.mfplatform.investor.dto.InvestorDtos.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * InvestorController.
 *
 * AUTHORIZATION LAYERS on DISTRIBUTOR-accessible endpoints:
 *
 *   Layer 1 — @PreAuthorize("hasAnyRole('ADMIN','DISTRIBUTOR')")
 *     Checked by Spring Security AOP (highest precedence).
 *     Rejects INVESTOR role and unauthenticated callers with 403.
 *
 *   Layer 2 — @RequireActiveDistributor
 *     Checked by DistributorStatusAspect AFTER Spring Security.
 *     If the caller is a DISTRIBUTOR in PENDING_VERIFICATION, REJECTED,
 *     or SUSPENDED status, throws DistributorNotActiveException → 403.
 *     ADMIN callers pass through this check unconditionally.
 *
 *   Layer 3 — service-level ownership (e.g. DISTRIBUTOR can only see/add
 *     investors in their own client book) — enforced inside the service.
 *
 * Before @RequireActiveDistributor existed, we had manual calls to
 * distributorService.assertDistributorActive() inside each method body.
 * The annotation removes that duplication — the aspect handles it once,
 * declaratively, at the call site.
 */
@Tag(name = "Investors", description = "Investor accounts. ADMIN sees all, DISTRIBUTOR sees client book only.")
@RestController
@RequestMapping("/api/v1/investors")
public class InvestorController {

    private final InvestorService investorService;
    private final AuthService authService;

    public InvestorController(InvestorService investorService, AuthService authService) {
        this.investorService = investorService;
        this.authService = authService;
    }

    /**
     * List investors — ADMIN sees all, DISTRIBUTOR sees their book.
     * @RequireActiveDistributor ensures a pending distributor cannot
     * browse investors even before they're fully verified.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','DISTRIBUTOR')")
    @RequireActiveDistributor
    public ResponseEntity<Page<InvestorResponse>> getInvestors(
            Authentication authentication,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(investorService.getInvestors(authentication, pageable));
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('INVESTOR')")
    public ResponseEntity<InvestorResponse> getMyself(Authentication authentication) {
        return ResponseEntity.ok(investorService.getSelf(authentication));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@investorSecurity.canView(#id, authentication)")
    public ResponseEntity<InvestorResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(investorService.getById(id));
    }

    /**
     * Privileged investor creation.
     * @RequireActiveDistributor prevents a PENDING_VERIFICATION distributor
     * from adding clients — they must be verified first.
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','DISTRIBUTOR')")
    @RequireActiveDistributor
    public ResponseEntity<InvestorResponse> addInvestor(
            @Valid @RequestBody AddInvestorRequest request,
            Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(authService.addInvestor(request, authentication));
    }
}
