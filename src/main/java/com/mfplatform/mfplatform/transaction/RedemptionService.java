package com.mfplatform.mfplatform.transaction;

import com.mfplatform.mfplatform.aspect.Auditable;
import com.mfplatform.mfplatform.common.BusinessDateService;
import com.mfplatform.mfplatform.folio.Folio;
import com.mfplatform.mfplatform.folio.FolioNotFoundException;
import com.mfplatform.mfplatform.folio.FolioRepository;
import com.mfplatform.mfplatform.investor.Investor;
import com.mfplatform.mfplatform.investor.InvestorNotFoundException;
import com.mfplatform.mfplatform.investor.InvestorRepository;
import com.mfplatform.mfplatform.scheme.Scheme;
import com.mfplatform.mfplatform.scheme.SchemeNotFoundException;
import com.mfplatform.mfplatform.scheme.SchemeRepository;
import com.mfplatform.mfplatform.security.ActorContext;
import com.mfplatform.mfplatform.transaction.dto.TransactionDtos.*;
import com.mfplatform.mfplatform.transaction.validation.RedemptionValidationChain;
import com.mfplatform.mfplatform.transaction.validation.TransactionValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * RedemptionService creates the PENDING transaction row for a REDEMPTION request.
 *
 * PIPELINE:
 *   1. Load context data (investor, folio, scheme, current holdings)
 *   2. Validate (KYC → folio ownership → scheme open for redemption
 *               → sufficient units → lock-in period)
 *   3. Create PENDING transaction row (units OR amount depending on redemption mode),
 *      stamped with the platform's current business date
 *   4. Return the PENDING transaction response
 *
 * SETTLEMENT MOVED TO EOD:
 * NAV determination and unit (de-)allotment used to run synchronously inside
 * this same call. They now happen in EodProcessingService, triggered
 * explicitly by an admin (or a production cron) once per business date —
 * same reasoning as PurchaseService. See EodProcessingService for the
 * settlement pipeline itself.
 *
 * TWO REDEMPTION MODES:
 *   - By units: investor specifies exact units to redeem (request.units non-null)
 *   - By amount: investor specifies INR amount to receive; service calculates
 *     the units to redeem (request.amount non-null, units null)
 *
 * Called by: TransactionService (after idempotency check)
 */
@Service
public class RedemptionService {

    private static final Logger log = LoggerFactory.getLogger(RedemptionService.class);

    private final MfTransactionRepository transactionRepository;
    private final InvestorRepository investorRepository;
    private final FolioRepository folioRepository;
    private final SchemeRepository schemeRepository;
    private final HoldingService holdingService;
    private final RedemptionValidationChain validationChain;
    private final BusinessDateService businessDateService;

    public RedemptionService(
            MfTransactionRepository transactionRepository,
            InvestorRepository investorRepository,
            FolioRepository folioRepository,
            SchemeRepository schemeRepository,
            HoldingService holdingService,
            RedemptionValidationChain validationChain,
            BusinessDateService businessDateService) {
        this.transactionRepository = transactionRepository;
        this.investorRepository = investorRepository;
        this.folioRepository = folioRepository;
        this.schemeRepository = schemeRepository;
        this.holdingService = holdingService;
        this.validationChain = validationChain;
        this.businessDateService = businessDateService;
    }

    /**
     * Executes the redemption request for a new (non-duplicate) request.
     */
    @Auditable(operation = "REDEMPTION")
    @Transactional
    public TransactionResponse createRedemption(RedemptionRequest request, ActorContext actor) {

        // ── Validate redemption mode ──────────────────────────────────────────
        // Exactly one of units/amount must be provided — cross-field validation
        // done here in the service rather than with annotations (cleaner for
        // cross-field rules where one field's validity depends on another).
        validateRedemptionMode(request);

        // ── Step 1: Load context data ─────────────────────────────────────────
        Folio folio = folioRepository.findById(request.folioId())
                .orElseThrow(() -> new FolioNotFoundException(request.folioId()));

        Investor investor = investorRepository.findById(folio.getInvestorId())
                .orElseThrow(() -> new InvestorNotFoundException(folio.getInvestorId()));

        Scheme scheme = schemeRepository.findById(request.schemeId())
                .orElseThrow(() -> new SchemeNotFoundException(request.schemeId()));

        var currentUnits = holdingService.getCurrentUnits(
                request.folioId(), request.schemeId());

        // ── Step 2: Validate ──────────────────────────────────────────────────
        // Chain: KYC → ownership → scheme open for redemption
        //      → sufficient units → lock-in period
        var context = new TransactionContext(
                investor, folio, scheme, actor,
                request.amount(), request.units(), currentUnits);

        validationChain.validate(context);

        // ── Step 3: Create PENDING transaction ────────────────────────────────
        // Store whichever mode was requested — both fields are stored when
        // present; UnitAllotmentService handles both in calculateUnits().
        // EodProcessingService picks this up (by businessDate) and settles it.
        MfTransaction transaction = transactionRepository.save(
                MfTransaction.builder()
                        .folioId(request.folioId())
                        .schemeId(request.schemeId())
                        .type(TransactionType.REDEMPTION)
                        .status(TransactionStatus.PENDING)
                        .requestUnits(request.units())
                        .requestAmount(request.amount())
                        .idempotencyKey(request.idempotencyKey())
                        .initiatedByUserId(actor.userId())
                        .initiatedByRole(InitiatedByRole.valueOf(actor.role().name()))
                        .requestedAt(Instant.now())
                        .businessDate(businessDateService.today())
                        .build()
        );

        log.info("Created PENDING redemption transaction {} for folio {} scheme {} businessDate {}",
                transaction.getId(), request.folioId(), request.schemeId(), transaction.getBusinessDate());

        return toResponse(transaction);
    }

    /**
     * Validates that exactly one of units/amount is provided in the request.
     *
     * Valid:   { units: 50.5, amount: null }  → redemption by units
     * Valid:   { units: null, amount: 10000 }  → redemption by amount
     * Invalid: { units: null, amount: null }   → neither provided
     * Invalid: { units: 50.5, amount: 10000 } → both provided (ambiguous)
     */
    private void validateRedemptionMode(RedemptionRequest request) {
        boolean hasUnits = request.units() != null;
        boolean hasAmount = request.amount() != null;

        if (!hasUnits && !hasAmount) {
            throw new TransactionValidationException(
                    "Redemption request must specify either units or amount.");
        }
        if (hasUnits && hasAmount) {
            throw new TransactionValidationException(
                    "Redemption request must specify units OR amount, not both. " +
                    "Use units to redeem an exact number of units, " +
                    "or amount to redeem a specific INR value.");
        }
    }

    /**
     * Maps MfTransaction to TransactionResponse DTO.
     * applicableNavValue/allottedUnits are null until EOD settles the transaction.
     */
    TransactionResponse toResponse(MfTransaction transaction) {
        return new TransactionResponse(
                transaction.getId(),
                transaction.getType().name(),
                transaction.getStatus().name(),
                transaction.getFolioId(),
                transaction.getSchemeId(),
                transaction.getRequestAmount(),
                transaction.getRequestUnits(),
                transaction.getAllottedUnits(),
                transaction.getApplicableNav() != null ? transaction.getApplicableNav().getNavValue() : null,
                transaction.getInitiatedByRole().name(),
                transaction.getRequestedAt(),
                transaction.getProcessedAt(),
                transaction.getBusinessDate()
        );
    }
}
