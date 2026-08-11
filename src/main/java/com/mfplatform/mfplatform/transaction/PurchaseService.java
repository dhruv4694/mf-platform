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
import com.mfplatform.mfplatform.transaction.validation.PurchaseValidationChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * PurchaseService creates the PENDING transaction row for a PURCHASE request.
 *
 * PIPELINE (in order):
 *   1. Load all context data (investor, folio, scheme, current holdings)
 *   2. Run the purchase validation chain (KYC → ownership → scheme open → min amount)
 *   3. Create PENDING transaction row (immutable ledger entry), stamped with the
 *      platform's current business date
 *   4. Return the PENDING transaction response
 *
 * SETTLEMENT MOVED TO EOD:
 * Payment simulation, NAV determination, and unit allotment used to run
 * synchronously inside this same call. They now happen in EodProcessingService,
 * triggered explicitly by an admin (or a production cron) once per business
 * date — this lets the platform demo realistic multi-day scenarios (SIP due
 * dates, NAV import lag, settlement backlogs) instead of settling everything
 * instantly. See ADR-007 for the async upgrade path this replaces, and
 * EodProcessingService for the settlement pipeline itself.
 *
 * TRANSACTION BOUNDARY:
 * The @Transactional on createPurchase() wraps the full method — context load,
 * validation, and the PENDING row insert. If any step fails, nothing commits.
 *
 * Called by: TransactionService (which handles idempotency check first)
 */
@Service
public class PurchaseService {

    private static final Logger log = LoggerFactory.getLogger(PurchaseService.class);

    private final MfTransactionRepository transactionRepository;
    private final InvestorRepository investorRepository;
    private final FolioRepository folioRepository;
    private final SchemeRepository schemeRepository;
    private final HoldingService holdingService;
    private final PurchaseValidationChain validationChain;
    private final BusinessDateService businessDateService;

    public PurchaseService(
            MfTransactionRepository transactionRepository,
            InvestorRepository investorRepository,
            FolioRepository folioRepository,
            SchemeRepository schemeRepository,
            HoldingService holdingService,
            PurchaseValidationChain validationChain,
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
     * Executes the purchase request for a new (non-duplicate) request.
     *
     * Called by TransactionService AFTER the idempotency check confirms
     * this is a genuinely new request (no existing row for the idempotency key).
     *
     * @param request  the validated purchase request
     * @param actor    the resolved caller identity from the JWT
     * @return the transaction response (status will be PENDING — settlement
     *         happens later via EodProcessingService)
     */
    @Auditable(operation = "PURCHASE")
    @Transactional
    public TransactionResponse createPurchase(PurchaseRequest request, ActorContext actor) {

        // ── Step 1: Load context data ─────────────────────────────────────────
        // Load all entities needed by the validation chain and pipeline.
        // Done once here so validators don't each make their own DB calls.
        Folio folio = folioRepository.findById(request.folioId())
                .orElseThrow(() -> new FolioNotFoundException(request.folioId()));

        Investor investor = investorRepository.findById(folio.getInvestorId())
                .orElseThrow(() -> new InvestorNotFoundException(folio.getInvestorId()));

        Scheme scheme = schemeRepository.findById(request.schemeId())
                .orElseThrow(() -> new SchemeNotFoundException(request.schemeId()));

        // Current units held — needed by validation chain even for purchases
        // (not for the purchase itself, but FolioOwnershipValidator uses the context)
        var currentUnits = holdingService.getCurrentUnits(
                request.folioId(), request.schemeId());

        // ── Step 2: Validate ──────────────────────────────────────────────────
        // Chain of Responsibility: KYC → folio ownership → scheme open → min amount
        // Any validator throws TransactionValidationException to stop the chain.
        var context = new TransactionContext(
                investor, folio, scheme, actor,
                request.amount(), null, currentUnits);

        validationChain.validate(context);

        // ── Step 3: Create PENDING transaction ────────────────────────────────
        // Insert the immutable ledger row. This records the investor's intent
        // regardless of what happens next. EodProcessingService picks this up
        // (by businessDate) and runs payment → NAV → allotment.
        MfTransaction transaction = transactionRepository.save(
                MfTransaction.builder()
                        .folioId(request.folioId())
                        .schemeId(request.schemeId())
                        .type(TransactionType.PURCHASE)
                        .status(TransactionStatus.PENDING)
                        .requestAmount(request.amount())
                        .idempotencyKey(request.idempotencyKey())
                        .initiatedByUserId(actor.userId())
                        .initiatedByRole(InitiatedByRole.valueOf(actor.role().name()))
                        .requestedAt(Instant.now())
                        .businessDate(businessDateService.today())
                        .build()
        );

        log.info("Created PENDING purchase transaction {} for folio {} scheme {} businessDate {}",
                transaction.getId(), request.folioId(), request.schemeId(), transaction.getBusinessDate());

        return toResponse(transaction);
    }

    /**
     * Maps MfTransaction to a TransactionResponse DTO.
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
                transaction.getBusinessDate(),
                transaction.getSipMandateId()
        );
    }
}
