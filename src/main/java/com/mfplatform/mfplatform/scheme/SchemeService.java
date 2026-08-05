package com.mfplatform.mfplatform.scheme;

import com.mfplatform.mfplatform.scheme.dto.SchemeDtos.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SchemeService owns all business logic related to schemes.
 *
 * Notably simpler than InvestorService and FolioService because:
 *   - No role-scoping: every authenticated caller sees the same full catalog
 *   - No cross-entity ownership checks: schemes aren't "owned" by any user
 *   - Authorization is entirely handled at the controller's @PreAuthorize level
 *
 * The one non-trivial rule enforced here is that schemeCode is immutable after
 * creation. This is enforced structurally — UpdateSchemeRequest simply does not
 * have a schemeCode field, so there's no way for the caller to change it even
 * if they tried.
 */
@Service
public class SchemeService {

    private final SchemeRepository schemeRepository;

    public SchemeService(SchemeRepository schemeRepository) {
        this.schemeRepository = schemeRepository;
    }

    /**
     * Returns all schemes, paginated.
     * Pageable is always passed in so the caller controls page size/sorting.
     * We never return an unbounded List<Scheme> — the scheme catalog could
     * theoretically be very large.
     */
    public Page<SchemeResponse> getSchemes(Pageable pageable) {
        return schemeRepository.findAll(pageable).map(this::toResponse);
    }

    /**
     * Returns a single scheme by id.
     * Throws SchemeNotFoundException (→ 404) if not found.
     */
    public SchemeResponse getById(Long id) {
        return schemeRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new SchemeNotFoundException(id));
    }

    /**
     * Creates a new scheme. @Transactional ensures the insert is rolled back
     * if anything fails mid-method (not strictly needed for a single save,
     * but a good habit to annotate all write methods consistently).
     */
    @Transactional
    public SchemeResponse createScheme(CreateSchemeRequest request) {
        Scheme scheme = schemeRepository.save(
                Scheme.builder()
                        .schemeName(request.schemeName())
                        .schemeCode(request.schemeCode())
                        .category(request.category())
                        .build()
        );
        return toResponse(scheme);
    }

    /**
     * Updates a scheme's mutable fields: schemeName, category.
     *
     * schemeCode is INTENTIONALLY excluded from UpdateSchemeRequest and
     * therefore from this method. Once a scheme code is assigned, it becomes
     * a stable identifier used by nav_history, transaction, holding, and
     * sip_mandate rows. Changing it would silently break all those references.
     *
     * Implementation note: we rebuild the entity using the Builder rather
     * than calling setters because the Scheme entity uses Lombok @Builder
     * without @Setter — making entities immutable-by-default means you
     * always know a full entity's state from its builder call, rather than
     * tracking which setters were called and in what order.
     */
    @Transactional
    public SchemeResponse updateScheme(Long id, UpdateSchemeRequest request) {
        Scheme existing = schemeRepository.findById(id)
                .orElseThrow(() -> new SchemeNotFoundException(id));

        // Preserve the id and schemeCode from the existing entity
        Scheme updated = Scheme.builder()
                .id(existing.getId())
                .schemeName(request.schemeName())
                .schemeCode(existing.getSchemeCode()) // carry over — not settable via request
                .category(request.category())
                .build();

        return toResponse(schemeRepository.save(updated));
    }

    /**
     * Hard-deletes a scheme. In practice this will fail with a 409 (FK violation
     * caught by GlobalExceptionHandler) if any nav_history, transaction, holding,
     * or sip_mandate row references this scheme.
     *
     * Real AMC systems would use a soft-delete (add an 'active' BOOLEAN column,
     * set it to false rather than DELETE) to preserve the audit trail. That would
     * be the right approach in production — documented here as a known trade-off.
     */
    @Transactional
    public void deleteScheme(Long id) {
        if (!schemeRepository.existsById(id)) {
            throw new SchemeNotFoundException(id);
        }
        schemeRepository.deleteById(id);
    }

    /**
     * Maps a Scheme entity to a SchemeResponse DTO.
     * Private — callers always receive DTOs, never raw JPA entities.
     */
    private SchemeResponse toResponse(Scheme scheme) {
        return new SchemeResponse(
                scheme.getId(),
                scheme.getSchemeName(),
                scheme.getSchemeCode(),
                scheme.getCategory()
        );
    }
}
