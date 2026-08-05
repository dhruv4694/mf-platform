package com.mfplatform.mfplatform.portfolio;

import com.mfplatform.mfplatform.common.Role;
import com.mfplatform.mfplatform.portfolio.dto.PortfolioDtos.*;
import com.mfplatform.mfplatform.security.ActorContext;
import com.mfplatform.mfplatform.security.CurrentUserResolver;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * PortfolioController exposes portfolio aggregation endpoints.
 *
 * ENDPOINTS:
 *   GET /portfolio/me                     → investor's own portfolio
 *   GET /portfolio/investors/{investorId} → specific investor's portfolio
 *                                           (ADMIN or their distributor)
 *   GET /portfolio/distributor/book       → distributor's full book summary
 *                                           (DISTRIBUTOR or ADMIN)
 *   GET /portfolio/folio/{folioId}        → one folio's portfolio
 *
 * All endpoints are read-only — portfolio is a derived view, never written to.
 */
@Tag(name = "Portfolio", description = "Portfolio aggregation — current value and returns across holdings.")
@RestController
@RequestMapping("/api/v1/portfolio")
public class PortfolioController {

    private final PortfolioService portfolioService;
    private final CurrentUserResolver currentUserResolver;
    private final com.mfplatform.mfplatform.folio.FolioRepository folioRepository;
    private final com.mfplatform.mfplatform.common.OwnershipValidator ownershipValidator;

    public PortfolioController(
            PortfolioService portfolioService,
            CurrentUserResolver currentUserResolver,
            com.mfplatform.mfplatform.folio.FolioRepository folioRepository,
            com.mfplatform.mfplatform.common.OwnershipValidator ownershipValidator) {
        this.portfolioService = portfolioService;
        this.currentUserResolver = currentUserResolver;
        this.folioRepository = folioRepository;
        this.ownershipValidator = ownershipValidator;
    }

    /**
     * Returns the portfolio for the currently logged-in investor.
     * Only INVESTOR role can call this — for ADMIN/DISTRIBUTOR use
     * GET /portfolio/investors/{investorId} instead.
     */
    @GetMapping("/me")
    @PreAuthorize("hasRole('INVESTOR')")
    public ResponseEntity<InvestorPortfolio> getMyPortfolio(Authentication authentication) {
        ActorContext actor = currentUserResolver.resolve(authentication);
        return ResponseEntity.ok(
                portfolioService.getInvestorPortfolio(actor.investorId()));
    }

    /**
     * Returns the portfolio for a specific investor.
     *
     * ACCESS:
     *   ADMIN       → any investor
     *   DISTRIBUTOR → only their own clients
     *   INVESTOR    → only themselves (redirect to /me instead)
     */
    @GetMapping("/investors/{investorId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<InvestorPortfolio> getInvestorPortfolio(
            @PathVariable Long investorId,
            Authentication authentication) {

        ActorContext actor = currentUserResolver.resolve(authentication);

        // Ownership check: DISTRIBUTOR can only view their own clients
        if (actor.role() == Role.DISTRIBUTOR) {
            if (!ownershipValidator.isDistributorClient(investorId, actor.distributorId())) {
                throw new AccessDeniedException(
                        "You can only view portfolios for investors in your client book.");
            }
        } else if (actor.role() == Role.INVESTOR) {
            // Investor can only view their own portfolio
            if (!investorId.equals(actor.investorId())) {
                throw new AccessDeniedException(
                        "You can only view your own portfolio. Use GET /portfolio/me.");
            }
        }

        return ResponseEntity.ok(portfolioService.getInvestorPortfolio(investorId));
    }

    /**
     * Returns the distributor's full book summary — one InvestorSummary per client.
     * Used for the distributor dashboard to show AUM, returns, and active SIPs
     * across all clients in one view.
     *
     * ADMIN can also call this to see any distributor's book by passing the
     * distributorId as a query param.
     */
    @GetMapping("/distributor/book")
    @PreAuthorize("hasAnyRole('DISTRIBUTOR', 'ADMIN')")
    public ResponseEntity<List<InvestorSummary>> getDistributorBook(
            @RequestParam(required = false) Long distributorId,
            Authentication authentication) {

        ActorContext actor = currentUserResolver.resolve(authentication);

        Long targetDistributorId;
        if (actor.role() == Role.DISTRIBUTOR) {
            // Distributor always sees their own book, ignoring any passed param
            targetDistributorId = actor.distributorId();
        } else {
            // ADMIN must provide a distributorId
            if (distributorId == null) {
                throw new IllegalArgumentException(
                        "distributorId query parameter is required for ADMIN.");
            }
            targetDistributorId = distributorId;
        }

        return ResponseEntity.ok(
                portfolioService.getDistributorBookSummary(targetDistributorId));
    }

    /**
     * Returns the portfolio for a single folio.
     * Ownership checked via FolioSecurity.
     */
    @GetMapping("/folio/{folioId}")
    @PreAuthorize("@folioSecurity.canView(#folioId, authentication)")
    public ResponseEntity<FolioPortfolio> getFolioPortfolio(@PathVariable Long folioId) {
        var folio = folioRepository.findById(folioId)
                .orElseThrow(() -> new com.mfplatform.mfplatform.folio.FolioNotFoundException(folioId));
        return ResponseEntity.ok(portfolioService.buildFolioPortfolio(folio));
    }
}
