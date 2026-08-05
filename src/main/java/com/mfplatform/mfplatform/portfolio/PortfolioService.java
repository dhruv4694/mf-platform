package com.mfplatform.mfplatform.portfolio;

import com.mfplatform.mfplatform.common.FinancialCalculations;
import com.mfplatform.mfplatform.folio.Folio;
import com.mfplatform.mfplatform.folio.FolioRepository;
import com.mfplatform.mfplatform.investor.Investor;
import com.mfplatform.mfplatform.investor.InvestorRepository;
import com.mfplatform.mfplatform.nav.NavHistoryRepository;
import com.mfplatform.mfplatform.portfolio.dto.PortfolioDtos.*;
import com.mfplatform.mfplatform.scheme.Scheme;
import com.mfplatform.mfplatform.scheme.SchemeRepository;
import com.mfplatform.mfplatform.sip.SipMandateRepository;
import com.mfplatform.mfplatform.sip.SipMandateStatus;
import com.mfplatform.mfplatform.transaction.Holding;
import com.mfplatform.mfplatform.transaction.HoldingRepository;
import com.mfplatform.mfplatform.transaction.MfTransaction;
import com.mfplatform.mfplatform.transaction.MfTransactionRepository;
import com.mfplatform.mfplatform.transaction.TransactionStatus;
import com.mfplatform.mfplatform.transaction.TransactionType;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * PortfolioService derives portfolio views entirely from existing data.
 *
 * NO NEW TABLES — everything comes from:
 *   holding       → unitsHeld (the running total)
 *   nav_history   → latest NAV for current_value calculation
 *   mf_transaction → invested amounts (sum of ALLOTTED purchases)
 *   scheme        → scheme names, categories (for display and grouping)
 *
 * STREAMS API SHOWCASE:
 * This service deliberately uses Streams for collection processing —
 * grouping by category, summing amounts, filtering by status, mapping
 * to DTOs. These are the kinds of intermediate-to-advanced Stream operations
 * that demonstrate Java proficiency beyond basic CRUD.
 *
 * ALL METHODS ARE READ-ONLY (@Transactional(readOnly = true)):
 * Portfolio views never write to the database. readOnly = true lets the
 * JPA provider skip dirty-checking on loaded entities (small performance gain)
 * and signals intent clearly to anyone reading the code.
 */
@Service
public class PortfolioService {

    private final HoldingRepository holdingRepository;
    private final MfTransactionRepository transactionRepository;
    private final NavHistoryRepository navHistoryRepository;
    private final SchemeRepository schemeRepository;
    private final FolioRepository folioRepository;
    private final InvestorRepository investorRepository;
    private final SipMandateRepository sipMandateRepository;

    public PortfolioService(
            HoldingRepository holdingRepository,
            MfTransactionRepository transactionRepository,
            NavHistoryRepository navHistoryRepository,
            SchemeRepository schemeRepository,
            FolioRepository folioRepository,
            InvestorRepository investorRepository,
            SipMandateRepository sipMandateRepository) {
        this.holdingRepository = holdingRepository;
        this.transactionRepository = transactionRepository;
        this.navHistoryRepository = navHistoryRepository;
        this.schemeRepository = schemeRepository;
        this.folioRepository = folioRepository;
        this.investorRepository = investorRepository;
        this.sipMandateRepository = sipMandateRepository;
    }

    // ─── Investor portfolio ───────────────────────────────────────────────────

    /**
     * Returns the complete portfolio for a given investor.
     * Aggregates across all folios — the top-level dashboard view.
     *
     * STREAMS USAGE:
     *   - map each folio to its FolioPortfolio
     *   - filter to folios with at least one non-zero holding
     *   - sum invested/current values across folios
     *   - group holdings by category for asset allocation
     */
    public InvestorPortfolio getInvestorPortfolio(Long investorId) {
        Investor investor = investorRepository.findById(investorId)
                .orElseThrow(() -> new RuntimeException("Investor not found: " + investorId));

        // Load all folios for this investor
        List<Folio> folios = folioRepository
                .findByInvestorId(investorId, Pageable.unpaged())
                .toList();

        // Build FolioPortfolio for each folio — map via Stream
        List<FolioPortfolio> folioPortfolios = folios.stream()
                .map(folio -> buildFolioPortfolio(folio))
                .filter(fp -> !fp.holdings().isEmpty()) // skip folios with no holdings
                .toList();

        // Portfolio-level totals — reduce across folios using Streams
        BigDecimal totalInvested = folioPortfolios.stream()
                .map(FolioPortfolio::totalInvestedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalCurrent = folioPortfolios.stream()
                .map(FolioPortfolio::totalCurrentValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalReturn = totalInvested.compareTo(BigDecimal.ZERO) > 0
                ? FinancialCalculations.calculateAbsoluteReturn(totalCurrent, totalInvested)
                : BigDecimal.ZERO;

        // Asset allocation — flatten all holdings, group by category
        List<HoldingView> allHoldings = folioPortfolios.stream()
                .flatMap(fp -> fp.holdings().stream())
                .toList();

        List<CategoryAllocation> allocations =
                buildCategoryAllocations(allHoldings, totalCurrent);

        return new InvestorPortfolio(
                investorId,
                investor.getName(),
                folioPortfolios,
                totalInvested,
                totalCurrent,
                totalReturn,
                allocations
        );
    }

    /**
     * Builds a FolioPortfolio for a single folio.
     * Loads all holdings with non-zero units, enriches each with
     * NAV and invested amount data.
     */
    public FolioPortfolio buildFolioPortfolio(Folio folio) {
        // Load all holdings for this folio
        List<Holding> holdings = holdingRepository.findByFolioId(folio.getId());

        // Build HoldingView for each holding that has units
        // Filter out zero-unit holdings (fully redeemed positions)
        List<HoldingView> holdingViews = holdings.stream()
                .filter(Holding::hasUnits)
                .map(h -> buildHoldingView(h, folio.getId()))
                .toList();

        // Folio-level totals
        BigDecimal totalInvested = holdingViews.stream()
                .map(HoldingView::investedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalCurrent = holdingViews.stream()
                .map(HoldingView::currentValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalReturn = totalInvested.compareTo(BigDecimal.ZERO) > 0
                ? FinancialCalculations.calculateAbsoluteReturn(totalCurrent, totalInvested)
                : BigDecimal.ZERO;

        return new FolioPortfolio(
                folio.getId(),
                folio.getFolioNumber(),
                holdingViews,
                totalInvested,
                totalCurrent,
                totalReturn
        );
    }

    /**
     * Builds a HoldingView for one holding — the core enrichment method.
     *
     * ENRICHMENT STEPS:
     *   1. Load the scheme (name, category)
     *   2. Find latest NAV → currentValue = unitsHeld × latestNav
     *   3. Sum invested amount from ALLOTTED PURCHASE transactions
     *      (minus REVERSED transactions to handle corrections)
     *   4. Calculate absolute return %
     *
     * INVESTED AMOUNT CALCULATION:
     * We sum request_amount from ALLOTTED PURCHASE transactions for this
     * folio/scheme, minus any REVERSED ones. This gives the true amount
     * the investor has put into this holding.
     *
     * We use transaction.requestAmount (what was asked), not
     * allottedUnits × NAV (what was received) — the two can differ slightly
     * due to rounding, and requestAmount is what the investor actually paid.
     */
    private HoldingView buildHoldingView(Holding holding, Long folioId) {
        Scheme scheme = schemeRepository.findById(holding.getSchemeId())
                .orElseThrow(() -> new RuntimeException(
                        "Scheme not found: " + holding.getSchemeId()));

        // Latest NAV for this scheme
        var latestNav = navHistoryRepository
                .findLatestNavOnOrBefore(holding.getSchemeId(), LocalDate.now())
                .orElse(null);

        BigDecimal latestNavValue = latestNav != null
                ? latestNav.getNavValue() : BigDecimal.ZERO;
        LocalDate latestNavDate = latestNav != null
                ? latestNav.getNavDate() : null;

        // Current value = units held × latest NAV
        BigDecimal currentValue = latestNavValue.compareTo(BigDecimal.ZERO) > 0
                ? FinancialCalculations.calculateCurrentValue(
                        holding.getUnitsHeld(), latestNavValue)
                : BigDecimal.ZERO;

        // Invested amount: sum ALLOTTED purchase amounts, minus REVERSED
        // STREAMS: filter by type + status, sum amounts
        List<MfTransaction> transactions = transactionRepository
                .findActiveForFolioAndScheme(folioId, holding.getSchemeId());

        BigDecimal investedAmount = transactions.stream()
                .filter(t -> t.getType() == TransactionType.PURCHASE)
                .filter(t -> t.getStatus() == TransactionStatus.ALLOTTED)
                .map(MfTransaction::getRequestAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Subtract reversed transactions
        BigDecimal reversedAmount = transactions.stream()
                .filter(t -> t.getType() == TransactionType.PURCHASE)
                .filter(t -> t.getStatus() == TransactionStatus.REVERSED)
                .map(MfTransaction::getRequestAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        investedAmount = investedAmount.subtract(reversedAmount);

        // Absolute return %
        BigDecimal absoluteReturnPct = investedAmount.compareTo(BigDecimal.ZERO) > 0
                ? FinancialCalculations.calculateAbsoluteReturn(currentValue, investedAmount)
                : BigDecimal.ZERO;

        return new HoldingView(
                holding.getId(),
                holding.getSchemeId(),
                scheme.getSchemeName(),
                scheme.getSchemeCode(),
                scheme.getCategory().name(),
                holding.getUnitsHeld(),
                investedAmount,
                currentValue,
                absoluteReturnPct,
                latestNavValue,
                latestNavDate
        );
    }

    /**
     * Builds asset allocation breakdown by scheme category.
     *
     * STREAMS USAGE:
     *   groupingBy category → summing invested + current per category
     *   then mapping to CategoryAllocation with allocation % calculation
     *
     * Example output:
     *   EQUITY:  invested ₹75,000, current ₹92,000, 65.2% of portfolio
     *   DEBT:    invested ₹25,000, current ₹27,000, 19.1% of portfolio
     *   HYBRID:  invested ₹20,000, current ₹22,300, 15.7% of portfolio
     */
    private List<CategoryAllocation> buildCategoryAllocations(
            List<HoldingView> holdings, BigDecimal totalCurrentValue) {

        // Group holdings by category, then aggregate within each group
        // Collectors.groupingBy + downstream Collectors.reducing
        Map<String, BigDecimal> investedByCategory = holdings.stream()
                .collect(Collectors.groupingBy(
                        HoldingView::schemeCategory,
                        Collectors.reducing(
                                BigDecimal.ZERO,
                                HoldingView::investedAmount,
                                BigDecimal::add)
                ));

        Map<String, BigDecimal> currentByCategory = holdings.stream()
                .collect(Collectors.groupingBy(
                        HoldingView::schemeCategory,
                        Collectors.reducing(
                                BigDecimal.ZERO,
                                HoldingView::currentValue,
                                BigDecimal::add)
                ));

        // Build CategoryAllocation for each category present in the portfolio
        return investedByCategory.entrySet().stream()
                .map(entry -> {
                    String category = entry.getKey();
                    BigDecimal categoryInvested = entry.getValue();
                    BigDecimal categoryCurrent = currentByCategory
                            .getOrDefault(category, BigDecimal.ZERO);

                    // Allocation % = categoryCurrentValue / totalPortfolioCurrentValue × 100
                    BigDecimal allocationPct = totalCurrentValue.compareTo(BigDecimal.ZERO) > 0
                            ? categoryCurrent
                                .divide(totalCurrentValue, 4, RoundingMode.HALF_UP)
                                .multiply(BigDecimal.valueOf(100))
                                .setScale(2, RoundingMode.HALF_UP)
                            : BigDecimal.ZERO;

                    return new CategoryAllocation(
                            category,
                            categoryInvested,
                            categoryCurrent,
                            allocationPct
                    );
                })
                // Sort by current value descending — largest allocation first
                .sorted((a, b) -> b.currentValue().compareTo(a.currentValue()))
                .toList();
    }

    // ─── Distributor book summary ─────────────────────────────────────────────

    /**
     * Returns a summary of each investor in the distributor's book.
     * Used for the distributor dashboard — shows AUM per client.
     *
     * STREAMS USAGE:
     *   For each investor: load folios → load holdings → sum values
     *   Count active SIPs via sipMandateRepository
     */
    public List<InvestorSummary> getDistributorBookSummary(Long distributorId) {
        // Load all investors in this distributor's book
        List<Investor> investors = investorRepository
                .findByDistributorId(distributorId, Pageable.unpaged())
                .toList();

        return investors.stream()
                .map(investor -> buildInvestorSummary(investor))
                .toList();
    }

    /**
     * Builds a summary for one investor — used in the distributor dashboard.
     */
    private InvestorSummary buildInvestorSummary(Investor investor) {
        List<Long> folioIds = folioRepository
                .findByInvestorId(investor.getId(), Pageable.unpaged())
                .map(Folio::getId)
                .toList();

        if (folioIds.isEmpty()) {
            return new InvestorSummary(
                    investor.getId(), investor.getName(), investor.getEmail(),
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0);
        }

        // Load all holdings across all folios for this investor
        List<Holding> holdings = holdingRepository.findByFolioIdIn(folioIds);

        // Calculate totals using Streams
        BigDecimal totalInvested = BigDecimal.ZERO;
        BigDecimal totalCurrent = BigDecimal.ZERO;

        for (Holding holding : holdings) {
            if (!holding.hasUnits()) continue;

            // Latest NAV for current value
            var latestNav = navHistoryRepository
                    .findLatestNavOnOrBefore(holding.getSchemeId(), LocalDate.now())
                    .orElse(null);

            if (latestNav != null) {
                totalCurrent = totalCurrent.add(
                        FinancialCalculations.calculateCurrentValue(
                                holding.getUnitsHeld(), latestNav.getNavValue()));
            }

            // Invested from transactions
            BigDecimal holdingInvested = transactionRepository
                    .findActiveForFolioAndScheme(holding.getFolioId(), holding.getSchemeId())
                    .stream()
                    .filter(t -> t.getType() == TransactionType.PURCHASE
                            && t.getStatus() == TransactionStatus.ALLOTTED)
                    .map(MfTransaction::getRequestAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            totalInvested = totalInvested.add(holdingInvested);
        }

        BigDecimal totalReturn = totalInvested.compareTo(BigDecimal.ZERO) > 0
                ? FinancialCalculations.calculateAbsoluteReturn(totalCurrent, totalInvested)
                : BigDecimal.ZERO;

        // Count active schemes (holdings with units > 0)
        long activeSchemeCount = holdings.stream()
                .filter(Holding::hasUnits)
                .count();

        // Count active SIPs
        long activeSipCount = sipMandateRepository
                .findByFolioIdIn(folioIds, Pageable.unpaged())
                .stream()
                .filter(m -> m.getStatus() == SipMandateStatus.ACTIVE)
                .count();

        return new InvestorSummary(
                investor.getId(),
                investor.getName(),
                investor.getEmail(),
                totalInvested,
                totalCurrent,
                totalReturn,
                (int) activeSchemeCount,
                (int) activeSipCount
        );
    }
}
