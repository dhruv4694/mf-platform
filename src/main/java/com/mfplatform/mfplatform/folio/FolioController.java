package com.mfplatform.mfplatform.folio;

import com.mfplatform.mfplatform.aspect.RequireActiveDistributor;
import com.mfplatform.mfplatform.folio.dto.FolioDtos.*;
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
 * FolioController maps HTTP requests to FolioService methods.
 *
 * A folio is a holding account for an investor — an investor must have at least
 * one folio before they can purchase or redeem units of any scheme. In real AMC
 * systems, folios are issued by the RTA (CAMS/KFintech); here we generate them
 * internally.
 *
 * Access control summary:
 *   GET  /folios       → all roles (ADMIN: all, DISTRIBUTOR: client book, INVESTOR: own)
 *   GET  /folios/{id}  → ownership-checked via FolioSecurity
 *   POST /folios       → all roles (each role's creation scope enforced in FolioService)
 *
 * Unlike GET /investors (which excludes INVESTOR role), GET /folios allows
 * INVESTOR because "my folios" is always a valid, self-scoped query — an investor
 * listing their own folios is expected and safe.
 */
@Tag(name = "Folios", description = "Investor folios — required before purchasing any scheme.")
@RestController
@RequestMapping("/api/v1/folios")
public class FolioController {

    private final FolioService folioService;

    public FolioController(FolioService folioService) {
        this.folioService = folioService;
    }

    /**
     * Returns folios scoped to the caller's role.
     * The role-scoping logic lives in FolioService, not here — this method
     * just passes the Authentication object through so the service can
     * extract the ActorContext and apply the right filter.
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Page<FolioResponse>> getFolios(
            Authentication authentication,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(folioService.getFolios(authentication, pageable));
    }

    /**
     * Returns a single folio by id.
     * FolioSecurity.canView() is evaluated by Spring AOP before this method runs.
     * If the caller doesn't own or have access to this folio, a 403 is returned
     * before the method body executes.
     */
    @GetMapping("/{id}")
    @PreAuthorize("@folioSecurity.canView(#id, authentication)")
    public ResponseEntity<FolioResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(folioService.getById(id));
    }

    /**
     * Creates a new folio. All roles can call this endpoint, but what they're
     * allowed to create differs:
     *   INVESTOR    → always creates for themselves (request.investorId ignored)
     *   DISTRIBUTOR → creates for a specified investor, who must be their own client
     *   ADMIN       → creates for any specified investor
     *
     * The per-role logic is enforced inside FolioService.createFolio(), not here.
     * The controller just validates the request shape and delegates.
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    @RequireActiveDistributor
    public ResponseEntity<FolioResponse> createFolio(
            @Valid @RequestBody CreateFolioRequest request,
            Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(folioService.createFolio(request, authentication));
    }
}
