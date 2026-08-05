package com.mfplatform.mfplatform.distributor;

import com.mfplatform.mfplatform.auth.AuthService;
import com.mfplatform.mfplatform.distributor.dto.DistributorDtos.*;
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
 * DistributorController.
 *
 * ACCESS CONTROL:
 *   GET  /distributors                → ADMIN (all), optionally filtered by status
 *   GET  /distributors/me             → DISTRIBUTOR (own profile, always allowed)
 *   GET  /distributors/{id}           → ADMIN or the distributor themselves
 *   POST /distributors                → ADMIN (creates ACTIVE immediately)
 *   PUT  /distributors/{id}/activate  → ADMIN (manual override)
 *   PUT  /distributors/{id}/suspend   → ADMIN
 *
 * Note: public signup is on AuthController (POST /auth/signup/distributor),
 * not here, since it requires no authentication.
 */
@Tag(name = "Distributors", description = "Distributor accounts and ARN verification lifecycle.")
@RestController
@RequestMapping("/api/v1/distributors")
public class DistributorController {

    private final DistributorService distributorService;
    private final AuthService authService;

    public DistributorController(
            DistributorService distributorService,
            AuthService authService) {
        this.distributorService = distributorService;
        this.authService = authService;
    }

    /**
     * List distributors. ADMIN can filter by status (e.g. ?status=PENDING_VERIFICATION
     * to see who is awaiting verification).
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Page<DistributorResponse>> getDistributors(
            @RequestParam(required = false) DistributorStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        if (status != null) {
            return ResponseEntity.ok(
                    distributorService.getDistributorsByStatus(status, pageable));
        }
        return ResponseEntity.ok(distributorService.getDistributors(pageable));
    }

    /**
     * Distributor views their own profile. Always allowed — status doesn't
     * block self-view (so they can see "PENDING_VERIFICATION" and understand
     * why other operations are blocked).
     */
    @GetMapping("/me")
    @PreAuthorize("hasRole('DISTRIBUTOR')")
    public ResponseEntity<DistributorResponse> getMyself(Authentication authentication) {
        return ResponseEntity.ok(distributorService.getSelf(authentication));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@distributorSecurity.canView(#id, authentication)")
    public ResponseEntity<DistributorResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(distributorService.getById(id));
    }

    /**
     * Admin creates a distributor directly as ACTIVE.
     * (Public self-signup is on POST /auth/signup/distributor instead.)
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<DistributorResponse> addDistributor(
            @Valid @RequestBody AddDistributorRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(authService.addDistributor(request));
    }

    /**
     * Admin manually activates a distributor.
     * Used to override the background worker or reinstate a suspended distributor.
     */
    @PutMapping("/{id}/activate")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<DistributorResponse> activate(@PathVariable Long id) {
        return ResponseEntity.ok(distributorService.activate(id));
    }

    /**
     * Admin suspends an active distributor (e.g. AMFI revoked their ARN).
     */
    @PutMapping("/{id}/suspend")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<DistributorResponse> suspend(@PathVariable Long id) {
        return ResponseEntity.ok(distributorService.suspend(id));
    }
}
