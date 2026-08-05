package com.mfplatform.mfplatform.distributor;

import com.mfplatform.mfplatform.distributor.dto.DistributorDtos.*;
import com.mfplatform.mfplatform.security.ActorContext;
import com.mfplatform.mfplatform.security.CurrentUserResolver;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * DistributorService owns all business logic for distributors.
 *
 * KEY ADDITION — public self-signup:
 * createDistributor() is now used by BOTH the public signup path
 * (POST /auth/signup/distributor) and the admin path (POST /distributors).
 *
 * The difference is the initial status:
 *   Public signup   → PENDING_VERIFICATION (needs background verification)
 *   Admin creation  → ACTIVE immediately (admin already verified out-of-band)
 *
 * STATUS GUARD (Option A enforcement):
 * assertDistributorActive() is called before any operation that requires
 * an ACTIVE distributor (adding investors, viewing client book).
 * Login is NOT blocked — only meaningful operations are.
 */
@Service
public class DistributorService {

    private final DistributorRepository distributorRepository;
    private final CurrentUserResolver currentUserResolver;

    public DistributorService(
            DistributorRepository distributorRepository,
            CurrentUserResolver currentUserResolver) {
        this.distributorRepository = distributorRepository;
        this.currentUserResolver = currentUserResolver;
    }

    // ─── Read operations ──────────────────────────────────────────────────────

    public Page<DistributorResponse> getDistributors(Pageable pageable) {
        return distributorRepository.findAll(pageable).map(this::toResponse);
    }

    public Page<DistributorResponse> getDistributorsByStatus(
            DistributorStatus status, Pageable pageable) {
        return distributorRepository.findByStatus(status, pageable).map(this::toResponse);
    }

    public DistributorResponse getSelf(Authentication auth) {
        ActorContext actor = currentUserResolver.resolve(auth);
        return distributorRepository.findById(actor.distributorId())
                .map(this::toResponse)
                .orElseThrow(() -> new DistributorNotFoundException(actor.distributorId()));
    }

    public DistributorResponse getById(Long id) {
        return distributorRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new DistributorNotFoundException(id));
    }

    // ─── Creation ─────────────────────────────────────────────────────────────

    /**
     * Creates a distributor business entity row.
     *
     * Used by two paths:
     *   1. Public signup (AuthService.signupDistributor):
     *      status = PENDING_VERIFICATION — awaits background verification
     *
     *   2. Admin creation (AuthService.addDistributor):
     *      status = ACTIVE — admin already verified the ARN out-of-band
     *
     * Returns the saved entity (not a DTO) so AuthService can extract
     * the id for UserService.createDistributorAccount().
     */
    @Transactional
    public Distributor createDistributor(
            String name, String arnCode, String email,
            DistributorStatus initialStatus) {
        return distributorRepository.save(
                Distributor.builder()
                        .name(name)
                        .arnCode(arnCode)
                        .email(email)
                        .status(initialStatus)
                        .submittedAt(Instant.now())
                        .createdAt(Instant.now())
                        .build()
        );
    }

    // ─── Status guard (Option A enforcement) ──────────────────────────────────

    /**
     * Asserts that the distributor with the given id is ACTIVE.
     * Throws DistributorNotActiveException if not — 403 via GlobalExceptionHandler.
     *
     * Called before any operation that requires an ACTIVE distributor:
     *   - Adding an investor (InvestorController.addInvestor)
     *   - Viewing client book (GET /investors, GET /folios, GET /transactions/my)
     *   - Creating a folio for a client (FolioController.createFolio)
     *
     * Not called for:
     *   - Login (Option A decision)
     *   - Viewing own profile (GET /distributors/me — always allowed)
     */
    public void assertDistributorActive(Long distributorId) {
        Distributor distributor = distributorRepository.findById(distributorId)
                .orElseThrow(() -> new DistributorNotFoundException(distributorId));

        if (!distributor.isActive()) {
            throw new DistributorNotActiveException(distributorId, distributor.getStatus());
        }
    }

    // ─── Admin status management ──────────────────────────────────────────────

    /**
     * Manually activates a distributor. ADMIN only.
     * Used to override the background verification or reinstate a suspended distributor.
     */
    @Transactional
    public DistributorResponse activate(Long id) {
        Distributor distributor = distributorRepository.findById(id)
                .orElseThrow(() -> new DistributorNotFoundException(id));
        distributor.activate();
        return toResponse(distributorRepository.save(distributor));
    }

    /**
     * Suspends an active distributor. ADMIN only.
     * e.g. when AMFI revokes their ARN.
     */
    @Transactional
    public DistributorResponse suspend(Long id) {
        Distributor distributor = distributorRepository.findById(id)
                .orElseThrow(() -> new DistributorNotFoundException(id));
        distributor.suspend();
        return toResponse(distributorRepository.save(distributor));
    }

    // ─── Internal — used by DistributorVerificationWorker ────────────────────

    /**
     * Activates a pending distributor after simulated ARN verification.
     * Called by DistributorVerificationWorker — not exposed as an endpoint.
     */
    @Transactional
    public void activateAfterVerification(Long id) {
        distributorRepository.findById(id).ifPresent(d -> {
            d.activate();
            distributorRepository.save(d);
        });
    }

    /**
     * Rejects a pending distributor after simulated ARN verification failure.
     * Called by DistributorVerificationWorker — not exposed as an endpoint.
     */
    @Transactional
    public void rejectAfterVerification(Long id) {
        distributorRepository.findById(id).ifPresent(d -> {
            d.reject();
            distributorRepository.save(d);
        });
    }

    public DistributorResponse toResponse(Distributor distributor) {
        return new DistributorResponse(
                distributor.getId(),
                distributor.getName(),
                distributor.getArnCode(),
                distributor.getEmail(),
                distributor.getStatus().name(),
                distributor.getSubmittedAt(),
                distributor.getVerifiedAt(),
                distributor.getCreatedAt()
        );
    }
}
