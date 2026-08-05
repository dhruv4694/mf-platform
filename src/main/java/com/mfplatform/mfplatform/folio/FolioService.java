package com.mfplatform.mfplatform.folio;

import com.mfplatform.mfplatform.common.OwnershipValidator;
import com.mfplatform.mfplatform.common.Role;
import com.mfplatform.mfplatform.folio.dto.FolioDtos.*;
import com.mfplatform.mfplatform.investor.InvestorRepository;
import com.mfplatform.mfplatform.security.ActorContext;
import com.mfplatform.mfplatform.security.CurrentUserResolver;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * FolioService owns all business logic related to folios.
 *
 * Key design decisions:
 *
 * 1. Role-scoped listing: GET /folios returns different data depending on who
 *    is asking, but via ONE service method. The role switch here mirrors the
 *    pattern used in InvestorService and TransactionService — consistency across
 *    the codebase makes the pattern easy to recognize.
 *
 * 2. Ownership-validated creation: what a caller is allowed to create differs
 *    by role, enforced here via OwnershipValidator rather than duplicating the
 *    ownership-check logic.
 *
 * 3. Folio number generation: simplified from the real-world RTA issuance model
 *    (where CAMS/KFintech issues sequential folio numbers) to a UUID-based
 *    unique identifier. The do-while loop ensures uniqueness without a DB
 *    sequence, at the cost of a small extra read on rare collisions.
 */
@Service
public class FolioService {

    private final FolioRepository folioRepository;
    private final InvestorRepository investorRepository;
    private final CurrentUserResolver currentUserResolver;
    private final OwnershipValidator ownershipValidator;

    public FolioService(
            FolioRepository folioRepository,
            InvestorRepository investorRepository,
            CurrentUserResolver currentUserResolver,
            OwnershipValidator ownershipValidator) {
        this.folioRepository = folioRepository;
        this.investorRepository = investorRepository;
        this.currentUserResolver = currentUserResolver;
        this.ownershipValidator = ownershipValidator;
    }

    /**
     * Returns folios scoped to the caller's role:
     *   ADMIN       → all folios in the system (unscoped)
     *   DISTRIBUTOR → all folios for investors in their client book
     *                 (uses a JOIN through investor.distributorId in the repository)
     *   INVESTOR    → only their own folios
     *
     * Note: unlike GET /investors which excludes INVESTOR role, here all three
     * roles can call this method because "my folios" is a valid self-scoped query
     * for an investor — they're listing their own data, not other people's.
     */
    public Page<FolioResponse> getFolios(Authentication auth, Pageable pageable) {
        ActorContext actor = currentUserResolver.resolve(auth);

        Page<Folio> page = switch (actor.role()) {
            case ADMIN -> folioRepository.findAll(pageable);
            case DISTRIBUTOR -> folioRepository.findByInvestorDistributorId(actor.distributorId(), pageable);
            case INVESTOR -> folioRepository.findByInvestorId(actor.investorId(), pageable);
        };

        return page.map(this::toResponse);
    }

    /**
     * Returns a single folio by id.
     * The caller's access to this specific id was already verified by
     * FolioSecurity.canView() via @PreAuthorize before this method was called.
     * So here we just do a plain lookup — no ownership re-check needed.
     */
    public FolioResponse getById(Long id) {
        return folioRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new FolioNotFoundException(id));
    }

    /**
     * Creates a new folio. The target investor depends on the caller's role:
     *
     *   INVESTOR    → always creates for themselves; request.investorId() is
     *                 ignored entirely (can't create a folio for someone else)
     *
     *   ADMIN       → creates for the investor specified in the request body;
     *                 can assign any investor, including direct ones (null distributor)
     *
     *   DISTRIBUTOR → creates for the investor specified in the request body,
     *                 but only if that investor is in their own client book.
     *                 OwnershipValidator.assertIsDistributorClient() throws
     *                 AccessDeniedException if the check fails — the exception
     *                 bubbles up and GlobalExceptionHandler returns a 403.
     *
     * The folio number is generated here (not by the DB or a sequence) because
     * real folio numbers come from the RTA in production. The UUID-based approach
     * is a reasonable stand-in for a portfolio project.
     */
    @Transactional
    public FolioResponse createFolio(CreateFolioRequest request, Authentication auth) {
        ActorContext actor = currentUserResolver.resolve(auth);

        Long targetInvestorId = switch (actor.role()) {

            case INVESTOR ->
                // INVESTOR always creates for themselves — request body ignored
                actor.investorId();

            case ADMIN ->
                // ADMIN trusts the request body's investorId
                request.investorId();

            case DISTRIBUTOR -> {
                // DISTRIBUTOR must specify an investorId, and that investor must
                // be in their client book. assertIsDistributorClient() throws
                // AccessDeniedException if they try to create a folio for
                // someone else's client.
                ownershipValidator.assertIsDistributorClient(
                        request.investorId(), actor.distributorId());
                yield request.investorId();
            }
        };

        Folio folio = folioRepository.save(
                Folio.builder()
                        .folioNumber(generateUniqueFolioNumber())
                        .investorId(targetInvestorId)
                        .createdAt(Instant.now())
                        .build()
        );

        return toResponse(folio);
    }

    /**
     * Generates a folio number that is guaranteed unique in the database.
     *
     * Format: "FL" + first 8 chars of a UUID, e.g. "FLA3F2B1C9"
     *
     * The do-while loop re-generates if the candidate already exists —
     * UUID collision probability is astronomically low, so in practice
     * this always exits on the first iteration. The loop is just defensive.
     *
     * In a real AMC system, folio numbers are issued by the RTA (CAMS/KFintech)
     * as sequential registry numbers and arrive via an API call. We document
     * that trade-off here rather than pretend this is production-ready.
     */
    private String generateUniqueFolioNumber() {
        String candidate;
        do {
            candidate = "FL" + UUID.randomUUID().toString()
                    .replace("-", "")
                    .substring(0, 8)
                    .toUpperCase();
        } while (folioRepository.existsByFolioNumber(candidate));
        return candidate;
    }

    /**
     * Maps a Folio entity to a FolioResponse DTO.
     * Private so callers always receive DTOs, never raw JPA entities.
     * This decouples the API contract from the persistence model —
     * if you rename a column in the entity, the DTO field name stays stable.
     */
    private FolioResponse toResponse(Folio folio) {
        return new FolioResponse(
                folio.getId(),
                folio.getFolioNumber(),
                folio.getInvestorId(),
                folio.getCreatedAt()
        );
    }
}
