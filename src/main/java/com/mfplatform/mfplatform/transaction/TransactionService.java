package com.mfplatform.mfplatform.transaction;

import com.mfplatform.mfplatform.folio.FolioRepository;
import com.mfplatform.mfplatform.security.ActorContext;
import com.mfplatform.mfplatform.security.CurrentUserResolver;
import com.mfplatform.mfplatform.common.Role;
import com.mfplatform.mfplatform.transaction.dto.TransactionDtos.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * TransactionService is the top-level orchestrator for all transaction operations.
 *
 * RESPONSIBILITIES:
 *   1. Idempotency check — before delegating to PurchaseService or
 *      RedemptionService, check if a transaction with this idempotency key
 *      already exists. If so, return it immediately without re-processing.
 *
 *   2. Routing — delegates to PurchaseService or RedemptionService based on
 *      the request type. TransactionService knows nothing about the purchase
 *      or redemption pipelines themselves — that's why those exist as separate
 *      services.
 *
 *   3. Role-scoped listing — GET /transactions/my returns different data
 *      depending on who is calling (ADMIN: all, DISTRIBUTOR: client book,
 *      INVESTOR: own folios). Same pattern as FolioService and InvestorService.
 *
 * WHY IDEMPOTENCY CHECK IS HERE (not in PurchaseService/RedemptionService):
 * Both purchase and redemption share the same idempotency mechanism —
 * look up by idempotency_key, return existing if found. Putting it here
 * means neither sub-service needs to know about it. TransactionService
 * checks first, then calls the right sub-service only for genuinely new requests.
 */
@Service
public class TransactionService {

    private final MfTransactionRepository transactionRepository;
    private final FolioRepository folioRepository;
    private final CurrentUserResolver currentUserResolver;
    private final PurchaseService purchaseService;
    private final RedemptionService redemptionService;

    public TransactionService(
            MfTransactionRepository transactionRepository,
            FolioRepository folioRepository,
            CurrentUserResolver currentUserResolver,
            PurchaseService purchaseService,
            RedemptionService redemptionService) {
        this.transactionRepository = transactionRepository;
        this.folioRepository = folioRepository;
        this.currentUserResolver = currentUserResolver;
        this.purchaseService = purchaseService;
        this.redemptionService = redemptionService;
    }

    /**
     * Initiates a purchase transaction.
     *
     * IDEMPOTENCY FIRST:
     * If a transaction with this idempotency key already exists, return it
     * immediately. This handles retried API calls gracefully — the investor
     * is never charged twice for the same logical purchase.
     *
     * @param request the purchase request (validated by controller)
     * @param auth    the caller's authentication
     * @return the transaction response (existing or newly created)
     */
    @Transactional
    public TransactionResponse purchase(PurchaseRequest request, Authentication auth) {
        ActorContext actor = currentUserResolver.resolve(auth);

        // Idempotency check — return existing transaction if key already used
        return transactionRepository
                .findByIdempotencyKey(request.idempotencyKey())
                .map(existing -> toResponse(existing))
                .orElseGet(() -> purchaseService.createPurchase(request, actor));
    }

    /**
     * Initiates a redemption transaction.
     * Same idempotency pattern as purchase().
     */
    @Transactional
    public TransactionResponse redeem(RedemptionRequest request, Authentication auth) {
        ActorContext actor = currentUserResolver.resolve(auth);

        return transactionRepository
                .findByIdempotencyKey(request.idempotencyKey())
                .map(existing -> toResponse(existing))
                .orElseGet(() -> redemptionService.createRedemption(request, actor));
    }

    /**
     * Returns transactions scoped to the caller's role:
     *   ADMIN       → all transactions in the system (unscoped)
     *   DISTRIBUTOR → all transactions for folios in their client book
     *   INVESTOR    → only transactions for their own folios
     *
     * This is the "GET /transactions/my" endpoint — one URL, role-scoped
     * inside the service. Same pattern used across FolioService, InvestorService.
     *
     * @Transactional(readOnly = true): keeps the persistence context open through
     * toResponse()'s mapping below, which lazily loads applicableNav for any
     * ALLOTTED transaction. Without this, that lazy load throws
     * LazyInitializationException — open-in-view is disabled (application.yml),
     * so nothing keeps the Hibernate session alive past the repository call
     * unless the service method itself is transactional.
     */
    @Transactional(readOnly = true)
    public Page<TransactionResponse> getMyTransactions(Authentication auth, Pageable pageable) {
        ActorContext actor = currentUserResolver.resolve(auth);

        return switch (actor.role()) {
            case ADMIN ->
                transactionRepository.findAll(pageable).map(this::toResponse);

            case INVESTOR -> {
                // Get all folio IDs for this investor, then fetch transactions
                List<Long> folioIds = folioRepository
                        .findByInvestorId(actor.investorId(), Pageable.unpaged())
                        .map(f -> f.getId())
                        .toList();
                yield transactionRepository
                        .findByFolioIdInOrderByRequestedAtDesc(folioIds, pageable)
                        .map(this::toResponse);
            }

            case DISTRIBUTOR -> {
                // Get all folio IDs across all of this distributor's clients
                List<Long> folioIds = folioRepository
                        .findByInvestorDistributorId(actor.distributorId(),
                                Pageable.unpaged())
                        .map(f -> f.getId())
                        .toList();
                yield transactionRepository
                        .findByFolioIdInOrderByRequestedAtDesc(folioIds, pageable)
                        .map(this::toResponse);
            }
        };
    }

    /**
     * Returns a single transaction by id.
     * Ownership is verified at the controller via @PreAuthorize("@folioSecurity...").
     *
     * @Transactional(readOnly = true) for the same reason as getMyTransactions().
     */
    @Transactional(readOnly = true)
    public TransactionResponse getById(Long id) {
        return transactionRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new TransactionNotFoundException(id));
    }

    /**
     * Maps MfTransaction to a response DTO.
     * applicableNav is loaded lazily via the entity relationship.
     */
    private TransactionResponse toResponse(MfTransaction txn) {
        return new TransactionResponse(
                txn.getId(),
                txn.getType().name(),
                txn.getStatus().name(),
                txn.getFolioId(),
                txn.getSchemeId(),
                txn.getRequestAmount(),
                txn.getRequestUnits(),
                txn.getAllottedUnits(),
                txn.getApplicableNav() != null
                        ? txn.getApplicableNav().getNavValue() : null,
                txn.getInitiatedByRole().name(),
                txn.getRequestedAt(),
                txn.getProcessedAt(),
                txn.getBusinessDate()
        );
    }
}
