package com.mfplatform.mfplatform.transaction;

import com.mfplatform.mfplatform.aspect.RequireActiveDistributor;
import com.mfplatform.mfplatform.transaction.dto.TransactionDtos.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * TransactionController maps HTTP requests to TransactionService.
 *
 * ACCESS CONTROL:
 *   POST /transactions/purchase    → any authenticated role
 *   POST /transactions/redemption  → any authenticated role
 *   GET  /transactions/my          → any authenticated role (role-scoped inside service)
 *   GET  /transactions/{id}        → ownership-checked via @folioSecurity
 *
 * The @PreAuthorize on purchase/redemption uses @folioSecurity.canTransact()
 * which delegates to OwnershipValidator — same bean used by FolioController.
 * This means the ownership check runs BEFORE TransactionService is called,
 * providing an early rejection for unauthorized access attempts.
 *
 * The idempotency check and pipeline execution happen inside TransactionService,
 * not here. This controller is intentionally thin — HTTP concerns only.
 */
@Tag(name = "Transactions", description = "Purchase and redemption of mutual fund units. " +
    "Units are allotted at the applicable NAV based on SEBI cutoff rules (3 PM for equity, 1:30 PM for debt).")
@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    /**
     * Initiates a purchase transaction.
     *
     * The @PreAuthorize checks that the caller has permission to transact
     * on the specified folio BEFORE the request reaches TransactionService.
     * If the caller doesn't own the folio (or it's not their client's folio),
     * a 403 is returned before any business logic runs.
     *
     * Returns 201 CREATED for a new transaction, or 200 OK for an idempotent
     * repeat (existing transaction returned). In practice both return the same
     * response body — the status code distinction is a nice-to-have that would
     * require inspecting whether the result was cached or new.
     * We use 201 for simplicity.
     */
    @Operation(
        summary = "Purchase units",
        description = "Creates a purchase transaction. Payment is simulated instantly. " +
            "Units are allotted at the applicable NAV based on SEBI cutoff rules. " +
            "Idempotency key prevents duplicate transactions — resubmitting the same key returns the existing transaction."
    )
    @ApiResponse(responseCode = "201", description = "Transaction created — check status field for allotment state")
    @ApiResponse(responseCode = "403", description = "Not your folio, or distributor not yet verified")
    @ApiResponse(responseCode = "422", description = "Validation failed — KYC incomplete, scheme closed, amount below minimum")
    @PostMapping("/purchase")
    @PreAuthorize("@folioSecurity.canTransact(#request.folioId(), authentication)")
    @RequireActiveDistributor
    public ResponseEntity<TransactionResponse> purchase(
            @Valid @RequestBody PurchaseRequest request,
            Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(transactionService.purchase(request, authentication));
    }

    /**
     * Initiates a redemption transaction.
     * Same ownership check pattern as purchase.
     *
     * Note: @Valid triggers validation on RedemptionRequest (notNull on folioId,
     * schemeId, idempotencyKey). Cross-field validation (exactly one of
     * units/amount must be provided) is handled in RedemptionService since
     * Bean Validation doesn't handle cross-field rules cleanly.
     */
    @Operation(
        summary = "Redeem units",
        description = "Redeems units from a holding. Specify either units (e.g. 50.0000) " +
            "or amount (e.g. 5000.00) — not both. Units are redeemed at applicable NAV."
    )
    @ApiResponse(responseCode = "201", description = "Redemption transaction created")
    @ApiResponse(responseCode = "422", description = "Insufficient units, or both/neither of units and amount provided")
    @PostMapping("/redemption")
    @PreAuthorize("@folioSecurity.canTransact(#request.folioId(), authentication)")
    @RequireActiveDistributor
    public ResponseEntity<TransactionResponse> redeem(
            @Valid @RequestBody RedemptionRequest request,
            Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(transactionService.redeem(request, authentication));
    }

    /**
     * Returns transactions scoped to the caller's role.
     * ADMIN: all transactions. DISTRIBUTOR: client book. INVESTOR: own folios.
     * Paginated — always pass page/size params to avoid unbounded queries.
     *
     * Sorted by businessDate descending — consistent with how the rest of the
     * system (EOD settlement, NAV lookup, SIP due dates) reasons about time
     * purely in terms of businessDate, never creation order. Since the
     * business date can move backward (see BusinessDateService), sorting by
     * database insertion order would show transactions out of chronological
     * sequence relative to their own businessDate.
     */
    @Operation(
        summary = "List transactions",
        description = "Returns transactions scoped to the caller's role. " +
            "INVESTOR: own transactions. DISTRIBUTOR: all client transactions. ADMIN: all transactions."
    )
    @GetMapping("/my")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Page<TransactionResponse>> getMyTransactions(
            Authentication authentication,
            @PageableDefault(size = 20, sort = "businessDate", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(
                transactionService.getMyTransactions(authentication, pageable));
    }

    /**
     * Returns a single transaction by id.
     * Uses @folioSecurity.canView() — checks that the caller has access to
     * the folio this transaction belongs to.
     *
     * Note: the #id here refers to the transaction id, not the folio id.
     * FolioSecurity.canView() accepts a folio id — TransactionService.getById()
     * must load the transaction's folioId first. This is handled inside
     * @folioSecurity by loading the transaction's folio transitively.
     * For simplicity in this project, we use a dedicated TransactionSecurity
     * bean (defined below) rather than adapting FolioSecurity.
     */
    @GetMapping("/{id}")
    @PreAuthorize("@transactionSecurity.canView(#id, authentication)")
    public ResponseEntity<TransactionResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(transactionService.getById(id));
    }
}
