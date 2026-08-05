package com.mfplatform.mfplatform.scheme;

import com.mfplatform.mfplatform.scheme.dto.SchemeDtos.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * SchemeController maps HTTP requests to SchemeService methods.
 *
 * A "scheme" is this fund house's term for what SEBI/AMFI call a mutual fund scheme —
 * e.g. "XYZ Equity Growth Fund - Direct Plan". Every scheme belongs to this AMC;
 * there is no amc_name column because the platform itself represents one AMC.
 *
 * Access control:
 *   READ  (GET)                → any authenticated role (ADMIN, DISTRIBUTOR, INVESTOR)
 *                                Schemes are the AMC's product catalog — all parties
 *                                need to browse it to make investment decisions.
 *   WRITE (POST, PUT, DELETE)  → ADMIN only
 *                                Only the fund house's own staff can create, modify,
 *                                or retire schemes. Distributors and investors are
 *                                consumers of the catalog, not editors.
 */
@Tag(name = "Schemes", description = "Mutual fund scheme catalog — equity, debt, and hybrid funds.")
@RestController
@RequestMapping("/api/v1/schemes")
public class SchemeController {

    private final SchemeService schemeService;

    public SchemeController(SchemeService schemeService) {
        this.schemeService = schemeService;
    }

    /**
     * Returns all schemes, paginated.
     * No role-scoping — every authenticated user sees the same catalog.
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Page<SchemeResponse>> getSchemes(
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(schemeService.getSchemes(pageable));
    }

    /**
     * Returns a single scheme by id.
     */
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<SchemeResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(schemeService.getById(id));
    }

    /**
     * Creates a new scheme. ADMIN only.
     * @Valid triggers validation annotations on CreateSchemeRequest before the
     * method body runs — if any field fails validation, Spring returns a 400
     * automatically via GlobalExceptionHandler.
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SchemeResponse> createScheme(
            @Valid @RequestBody CreateSchemeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(schemeService.createScheme(request));
    }

    /**
     * Updates an existing scheme. ADMIN only.
     * Note: schemeCode is NOT in UpdateSchemeRequest — it is intentionally
     * immutable after creation. Changing a scheme code would break references
     * in nav_history, transaction, and holding tables.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SchemeResponse> updateScheme(
            @PathVariable Long id,
            @Valid @RequestBody UpdateSchemeRequest request) {
        return ResponseEntity.ok(schemeService.updateScheme(id, request));
    }

    /**
     * Deletes a scheme. ADMIN only.
     * In practice, if any transaction, holding, or nav_history row references
     * this scheme, the DB's foreign key constraint will prevent deletion and
     * surface as a 409 via GlobalExceptionHandler. So this effectively only
     * works for newly created schemes with no history yet.
     * A real AMC system would soft-delete (add an 'active' flag) rather than
     * hard-delete, to preserve the audit trail.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteScheme(@PathVariable Long id) {
        schemeService.deleteScheme(id);
        return ResponseEntity.noContent().build();
    }
}
