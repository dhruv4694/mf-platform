package com.mfplatform.mfplatform.investor;

import com.mfplatform.mfplatform.common.OwnershipValidator;
import com.mfplatform.mfplatform.common.Role;
import com.mfplatform.mfplatform.investor.dto.InvestorDtos.*;
import com.mfplatform.mfplatform.security.ActorContext;
import com.mfplatform.mfplatform.security.CurrentUserResolver;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * InvestorService owns business logic related to the investor entity ONLY.
 *
 * After the Option B refactor:
 *   - This service no longer touches user_account. That is UserService's job.
 *   - AuthService orchestrates calling InvestorService + UserService together
 *     inside a single @Transactional boundary.
 *   - This service's createInvestor() returns the saved Investor so AuthService
 *     can pass investor.getId() to UserService.createInvestorAccount().
 */
@Service
public class InvestorService {

    private final InvestorRepository investorRepository;
    private final CurrentUserResolver currentUserResolver;
    private final OwnershipValidator ownershipValidator;

    public InvestorService(
            InvestorRepository investorRepository,
            CurrentUserResolver currentUserResolver,
            OwnershipValidator ownershipValidator) {
        this.investorRepository = investorRepository;
        this.currentUserResolver = currentUserResolver;
        this.ownershipValidator = ownershipValidator;
    }

    /**
     * Returns investors scoped to whoever is asking:
     *   ADMIN       → all investors (unscoped)
     *   DISTRIBUTOR → only investors in their client book
     * INVESTOR role is excluded at the controller's @PreAuthorize level.
     */
    public Page<InvestorResponse> getInvestors(Authentication auth, Pageable pageable) {
        ActorContext actor = currentUserResolver.resolve(auth);

        Page<Investor> page = (actor.role() == Role.ADMIN)
                ? investorRepository.findAll(pageable)
                : investorRepository.findByDistributorId(actor.distributorId(), pageable);

        return page.map(this::toResponse);
    }

    /**
     * Returns the investor record for the currently logged-in investor.
     * Identity comes from the JWT — no URL parameter, so no risk of
     * an investor querying another investor's record.
     */
    public InvestorResponse getSelf(Authentication auth) {
        ActorContext actor = currentUserResolver.resolve(auth);
        return investorRepository.findById(actor.investorId())
                .map(this::toResponse)
                .orElseThrow(() -> new InvestorNotFoundException(actor.investorId()));
    }

    public InvestorResponse getById(Long id) {
        return investorRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new InvestorNotFoundException(id));
    }

    /**
     * Creates ONLY the investor business entity row.
     *
     * Does NOT create the user_account — that is UserService's responsibility.
     * AuthService calls this method then immediately calls
     * UserService.createInvestorAccount() with the returned investor's id.
     * Both calls are wrapped in AuthService's @Transactional boundary.
     *
     * @return the saved Investor entity (not a DTO) so AuthService can
     *         extract investor.getId() for the UserService call
     */
    @Transactional
    public Investor createInvestor(
            String name, String email, String panNumber, Long distributorId) {
        return investorRepository.save(
                Investor.builder()
                        .name(name)
                        .email(email)
                        .panNumber(panNumber)
                        .distributorId(distributorId)
                        .createdAt(Instant.now())
                        .build()
        );
    }

    /**
     * Privileged path: ADMIN or DISTRIBUTOR adding a client.
     * Determines the correct distributorId based on caller's role,
     * then delegates to createInvestor().
     * AuthService handles the UserService call after this returns.
     */
    public Investor addInvestor(AddInvestorRequest request, Authentication auth) {
        ActorContext actor = currentUserResolver.resolve(auth);

        Long distributorId = (actor.role() == Role.DISTRIBUTOR)
                ? actor.distributorId()
                : request.distributorId();

        return createInvestor(
                request.name(), request.email(),
                request.panNumber(), distributorId
        );
    }

    public InvestorResponse toResponse(Investor investor) {
        return new InvestorResponse(
                investor.getId(),
                investor.getName(),
                investor.getEmail(),
                investor.getPanNumber(),
                investor.getDistributorId(),
                investor.isKycComplete(),   // was missing — InvestorsPage needs this
                investor.getCreatedAt()
        );
    }
}
