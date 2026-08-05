package com.mfplatform.mfplatform.transaction;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * Holding stores the current unit balance per (folio, scheme) pair.
 *
 * KEY DESIGN POINTS (see ADR-002):
 *
 * 1. NOT THE SOURCE OF TRUTH — MfTransaction is.
 *    Holding is a derived running total maintained for query performance.
 *    If all Holding rows were deleted, they could be fully reconstructed
 *    by replaying all ALLOTTED and REVERSED transactions.
 *
 * 2. ONLY unitsHeld IS STORED.
 *    current_value, invested_amount, and returns are NEVER stored here.
 *    They're calculated at query time:
 *      current_value    = unitsHeld × latest NAV
 *      invested_amount  = SUM(transaction.requestAmount) for ALLOTTED purchases
 *      returns          = (current_value - invested_amount) / invested_amount
 *    Storing these would mean they go stale the moment a new NAV is imported.
 *
 * 3. @Version OPTIMISTIC LOCKING:
 *    Two concurrent allotments (e.g. two SIP installments hitting the same
 *    folio/scheme) both try to update unitsHeld. @Version ensures only one
 *    can succeed per write cycle. The other gets OptimisticLockException
 *    and must RETRY (unlike MfTransaction where conflicts mean "exit safely").
 *
 *    WHY RETRY (not exit) FOR HOLDING:
 *    The allotting worker already exclusively claimed its MfTransaction via
 *    claimForAllotment(). It MUST update the holding — that's its job.
 *    The conflict just means another worker updated the holding first.
 *    Retry with the fresh unitsHeld value to apply this allotment on top.
 *
 * 4. getOrCreate PATTERN:
 *    When a folio purchases a scheme for the first time, no Holding row
 *    exists yet. HoldingService.getOrCreate() either finds the existing row
 *    or creates one with unitsHeld = 0. This is also done inside a
 *    @Transactional boundary to prevent two concurrent first-purchases
 *    from both trying to INSERT (which would violate UNIQUE(folio_id, scheme_id)).
 */
@Entity
@Table(
    name = "holding",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_holding_folio_scheme",
        columnNames = {"folio_id", "scheme_id"}
    )
)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Holding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The folio this holding belongs to.
     * Together with schemeId, forms the unique key for a holding.
     */
    @Column(name = "folio_id", nullable = false, updatable = false)
    private Long folioId;

    /**
     * The scheme whose units are held.
     */
    @Column(name = "scheme_id", nullable = false, updatable = false)
    private Long schemeId;

    /**
     * Current unit balance. The ONLY mutable business field on this entity.
     * Updated atomically with @Version guard on every allotment/redemption.
     *
     * BigDecimal with 4 decimal places — consistent with MfTransaction.allottedUnits
     * and NavHistory.navValue. Financial arithmetic must never use floating point.
     */
    @Column(name = "units_held", nullable = false, precision = 12, scale = 4)
    @Builder.Default
    private BigDecimal unitsHeld = BigDecimal.ZERO;

    /**
     * Optimistic locking version — managed entirely by Hibernate.
     * Incremented on every UPDATE. If two concurrent writes try to update
     * the same version, the second throws OptimisticLockException.
     *
     * Application code never reads or sets this directly.
     */
    @Version
    @Column(nullable = false)
    private Long version;

    // ─── Domain methods ───────────────────────────────────────────────────────

    /**
     * Adds units after a successful purchase allotment.
     * Called by UnitAllotmentService.allot() inside a @Transactional boundary.
     *
     * @param units the units to add (must be positive)
     * @throws IllegalArgumentException if units is not positive
     */
    public void addUnits(BigDecimal units) {
        if (units.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    "Units to add must be positive, got: " + units);
        }
        this.unitsHeld = this.unitsHeld.add(units);
    }

    /**
     * Subtracts units after a redemption.
     * Called by UnitAllotmentService.redeem() inside a @Transactional boundary.
     *
     * @param units the units to subtract (must be positive and <= current balance)
     * @throws InsufficientUnitsException if redemption would make balance negative
     */
    public void subtractUnits(BigDecimal units) {
        if (units.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    "Units to subtract must be positive, got: " + units);
        }
        if (units.compareTo(this.unitsHeld) > 0) {
            throw new InsufficientUnitsException(
                    "Cannot redeem " + units + " units — only " + unitsHeld + " held");
        }
        this.unitsHeld = this.unitsHeld.subtract(units);
    }

    /**
     * Whether this holding has any units (i.e. investor still has a position).
     * A holding with zero units is a valid state — it means the investor fully
     * redeemed but may purchase again in the future.
     */
    public boolean hasUnits() {
        return unitsHeld.compareTo(BigDecimal.ZERO) > 0;
    }
}
