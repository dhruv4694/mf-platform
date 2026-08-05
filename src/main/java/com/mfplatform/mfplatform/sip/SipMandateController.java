package com.mfplatform.mfplatform.sip;

import com.mfplatform.mfplatform.sip.dto.SipDtos.*;
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
 * SipMandateController.
 *
 * ACCESS CONTROL:
 *   POST   /sip-mandates           → all authenticated roles (ownership checked in service)
 *   GET    /sip-mandates/my        → all authenticated roles (role-scoped)
 *   GET    /sip-mandates/{id}      → ownership checked via @sipMandateSecurity
 *   PUT    /sip-mandates/{id}/pause   → ownership checked in service
 *   PUT    /sip-mandates/{id}/resume  → ownership checked in service
 *   DELETE /sip-mandates/{id}         → ownership checked in service (cancel)
 *
 * Note: we use DELETE for cancel (irreversible) and PUT for pause/resume
 * (reversible state changes). This follows REST semantics correctly:
 *   - PUT = idempotent state update (pausing an already-paused SIP → no-op or same result)
 *   - DELETE = removal / permanent termination
 */
@Tag(name = "SIP Mandates", description = "Systematic Investment Plans — recurring fixed-amount purchases.")
@RestController
@RequestMapping("/api/v1/sip-mandates")
public class SipMandateController {

    private final SipMandateService sipMandateService;

    public SipMandateController(SipMandateService sipMandateService) {
        this.sipMandateService = sipMandateService;
    }

    /**
     * Registers a new SIP mandate.
     * Ownership of the folio is verified inside SipMandateService.
     * Duplicate check (active SIP for same folio/scheme) is also inside the service.
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<SipMandateResponse> register(
            @Valid @RequestBody RegisterSipRequest request,
            Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(sipMandateService.register(request, authentication));
    }

    /**
     * Returns SIP mandates scoped to the caller's role.
     * ADMIN: all mandates. DISTRIBUTOR: client book. INVESTOR: own folios.
     */
    @GetMapping("/my")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Page<SipMandateResponse>> getMySipMandates(
            Authentication authentication,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(
                sipMandateService.getMySipMandates(authentication, pageable));
    }

    /**
     * Returns a single SIP mandate by id.
     */
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<SipMandateResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(sipMandateService.getById(id));
    }

    /**
     * Pauses an active SIP. Reversible.
     * Installments are skipped while paused. Schedule still advances
     * so resuming picks up on the correct date.
     */
    @PutMapping("/{id}/pause")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<SipMandateResponse> pause(
            @PathVariable Long id, Authentication authentication) {
        return ResponseEntity.ok(sipMandateService.pause(id, authentication));
    }

    /**
     * Resumes a paused SIP.
     */
    @PutMapping("/{id}/resume")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<SipMandateResponse> resume(
            @PathVariable Long id, Authentication authentication) {
        return ResponseEntity.ok(sipMandateService.resume(id, authentication));
    }

    /**
     * Permanently cancels a SIP. Irreversible.
     * Returns the final state of the mandate (status: CANCELLED).
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<SipMandateResponse> cancel(
            @PathVariable Long id, Authentication authentication) {
        return ResponseEntity.ok(sipMandateService.cancel(id, authentication));
    }
}
