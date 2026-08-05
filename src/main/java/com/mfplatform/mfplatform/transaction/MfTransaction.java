package com.mfplatform.mfplatform.transaction;

import com.mfplatform.mfplatform.nav.NavHistory;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * MfTransaction is the central entity of the system — the immutable financial ledger.
 *
 * DESIGN PRINCIPLES (see ADR-001, ADR-003):
 *
 * 1. APPEND-ONLY CORE FIELDS:
 *    folio_id, scheme_id, type, idempotency_key, request_amount, request_units,
 *    initiated_by_user_id, initiated_by_role, sip_mandate_id, requested_at,
 *    business_date
 *    are set at INSERT time and never changed. They describe what was requested.
 *
 * 2. PIPELINE FIELDS (updated as transaction progresses):
 *    status, applicable_nav_id, allotted_units, processed_at
 *    are updated by the processing pipeline. They describe what happened.
 *
 * 3. OPTIMISTIC LOCKING (@Version):
 *    Prevents accidental concurrent edits as a safety net.
 *    The PRIMARY concurrency mechanism is the compare-and-set claim query
 *    (claimForAllotment) — @Version is the secondary safety net. See ADR-003.
 *
 * 4. IDEMPOTENCY KEY:
 *    UNIQUE constraint ensures the same logical operation can never create
 *    two transaction rows. If an API call is retried (network timeout, double-tap),
 *    the second attempt finds the existing row and returns it.
 *
 * 5. STATE TRANSITIONS via transitionTo():
 *    Status changes must go through TransactionStatus.transitionTo() which
 *    enforces valid transitions. Direct setStatus() is not exposed.
 *
 * 6. TABLE NAME: mf_transaction (not "transaction" — reserved SQL keyword)
 *
 * PURCHASE vs REDEMPTION:
 *   PURCHASE:   request_amount is set, request_units is null
 *   REDEMPTION: request_units is set, request_amount may also be set
 *               (redemption by amount: "redeem ₹10,000 worth")
 *   The DB CHECK constraint enforces this — see V1__init_schema.sql
 */
@Entity
@Table(
    name = "mf_transaction",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_transaction_idempotency_key",
        columnNames = "idempotency_key"
    )
)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MfTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ─── Immutable core fields (set at INSERT, never changed) ─────────────────

    /**
     * The folio this transaction applies to.
     * Determines which investor's holding is affected.
     */
    @Column(name = "folio_id", nullable = false, updatable = false)
    private Long folioId;

    /**
     * The scheme being purchased or redeemed.
     */
    @Column(name = "scheme_id", nullable = false, updatable = false)
    private Long schemeId;

    /**
     * PURCHASE or REDEMPTION.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private TransactionType type;

    /**
     * Amount in INR for purchase transactions (₹5000, ₹10000 etc).
     * Null for redemption-by-units.
     */
    @Column(name = "request_amount", precision = 12, scale = 2, updatable = false)
    private BigDecimal requestAmount;

    /**
     * Units for redemption transactions (e.g. "redeem 50.5 units").
     * For redemption-by-amount, this is null (amount is set instead).
     * For SIP purchases, this is null (amount drives allotment).
     */
    @Column(name = "request_units", precision = 12, scale = 4, updatable = false)
    private BigDecimal requestUnits;

    /**
     * Prevents duplicate processing of retried API calls.
     * Client generates a UUID per logical operation. If the same key appears
     * twice, the second request returns the existing transaction row.
     * UNIQUE constraint enforced both here and in the DB schema.
     */
    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    /**
     * The user_account.id of whoever submitted this transaction.
     * Paired with initiatedByRole to form the complete audit picture.
     * See ADR-004 for why we track the actor separately from the investor.
     */
    @Column(name = "initiated_by_user_id", nullable = false, updatable = false)
    private Long initiatedByUserId;

    /**
     * Denormalized snapshot of the actor's role at transaction creation time.
     * Immutable — even if the user's role changes later, this preserves the
     * historical fact of who placed this transaction. See InitiatedByRole javadoc.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "initiated_by_role", nullable = false, updatable = false)
    private InitiatedByRole initiatedByRole;

    /**
     * If this transaction was originated by a SIP mandate, this links to it.
     * Null for investor/distributor-initiated transactions.
     * See ADR-006 — SIP as a standing instruction, not a transaction type.
     */
    @Column(name = "sip_mandate_id", updatable = false)
    private Long sipMandateId;

    /**
     * If this is a reversal, points to the original transaction being reversed.
     * Reversals are new rows — never edits to the original. See ADR-001.
     */
    @Column(name = "reversal_of_id", updatable = false)
    private Long reversalOfId;

    /**
     * When the transaction request was received.
     */
    @Column(name = "requested_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant requestedAt = Instant.now();

    /**
     * The platform's business date at the moment this transaction was created
     * (from BusinessDateService.today(), not the real system clock).
     * EOD settlement processes PENDING transactions by this field, not by
     * requestedAt — see EodProcessingService.
     */
    @Column(name = "business_date", nullable = false, updatable = false)
    private LocalDate businessDate;

    // ─── Pipeline fields (updated as transaction progresses) ──────────────────

    /**
     * Current lifecycle status. Protected by both:
     *   1. State pattern (TransactionStatus.transitionTo enforces valid transitions)
     *   2. @Version optimistic locking (secondary safety net)
     *   3. Compare-and-set claim query (primary multi-worker safety mechanism)
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionStatus status;

    /**
     * The exact NavHistory record whose nav_value was used for allotment.
     * Null until EodProcessingService resolves it (by exact scheme+businessDate match).
     * Once set, this permanently records "this is the NAV that was used" —
     * even if future NAVs are imported, this transaction's applicable NAV
     * is permanently linked here.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "applicable_nav_id")
    private NavHistory applicableNav;

    /**
     * Units allotted for purchase, or units redeemed for redemption.
     * For purchase: allottedUnits = requestAmount / applicableNav.navValue
     * For redemption-by-units: allottedUnits = requestUnits (directly)
     * For redemption-by-amount: allottedUnits = requestAmount / applicableNav.navValue
     *
     * Null until UnitAllotmentService completes.
     */
    @Column(name = "allotted_units", precision = 12, scale = 4)
    private BigDecimal allottedUnits;

    /**
     * When the transaction was fully processed (reached ALLOTTED or FAILED).
     * Null while still in the pipeline.
     */
    @Column(name = "processed_at")
    private Instant processedAt;

    /**
     * Optimistic locking column — managed entirely by Hibernate.
     * Never set or read directly in application code.
     * Hibernate increments this on every UPDATE. If two concurrent writes
     * try to update the same version, the second throws OptimisticLockException.
     */
    @Version
    @Column(nullable = false)
    private Long version;

    // ─── State transition methods ─────────────────────────────────────────────

    /**
     * The ONLY way to change a transaction's status.
     * Delegates to TransactionStatus.transitionTo() which enforces valid
     * transitions via the State pattern.
     *
     * Usage:
     *   transaction.transitionTo(TransactionStatus.PAYMENT_REALIZED);
     *   // throws InvalidTransactionStateException if current status doesn't
     *   // allow transition to PAYMENT_REALIZED
     */
    public void transitionTo(TransactionStatus next) {
        this.status = this.status.transitionTo(next);
    }

    /**
     * Sets the applicable NAV once EOD settlement resolves it.
     * Called only when status is transitioning to NAV_APPLIED.
     */
    public void applyNav(NavHistory nav) {
        this.applicableNav = nav;
        this.transitionTo(TransactionStatus.NAV_APPLIED);
    }

    /**
     * Sets the allotted units and marks the transaction as fully processed.
     * Called only by UnitAllotmentService after Holding is updated.
     * Both changes happen in the same @Transactional call.
     */
    public void markAllotted(BigDecimal units) {
        this.allottedUnits = units;
        this.transitionTo(TransactionStatus.ALLOTTED);
        this.processedAt = Instant.now();
    }

    /**
     * Marks the transaction as failed with no allotment.
     * Can be called from any non-terminal status.
     */
    public void markFailed() {
        this.transitionTo(TransactionStatus.FAILED);
        this.processedAt = Instant.now();
    }
}
