package com.mfplatform.mfplatform.scheme;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Scheme represents a mutual fund scheme offered by this fund house.
 *
 * Fields added beyond the original design:
 *   isOpenForPurchase / isOpenForRedemption — scheme-level open/close flags
 *   minimumPurchaseAmount — SEBI requires AMCs to publish minimum amounts
 *   lockInEndDate — for ELSS schemes only (3-year mandatory lock-in)
 *
 * schemeCode is immutable post-creation (updatable = false) — it's used as
 * a stable identifier in nav_history, mf_transaction, and holding. Changing
 * it would silently break all those references. See SchemeService.updateScheme().
 */
@Entity
@Table(name = "scheme")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Scheme {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scheme_name", nullable = false)
    private String schemeName;

    /** Immutable after creation — see updatable = false */
    @Column(name = "scheme_code", nullable = false, unique = true, updatable = false)
    private String schemeCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SchemeCategory category;

    /**
     * Whether this scheme is currently accepting purchase transactions.
     * Admin can toggle this (e.g. during NFO allotment, or AUM cap reached).
     */
    @Column(name = "open_for_purchase", nullable = false)
    @Builder.Default
    private boolean openForPurchase = true;

    /**
     * Whether this scheme is currently accepting redemption transactions.
     * SEBI allows temporary gating of redemptions in stress scenarios.
     */
    @Column(name = "open_for_redemption", nullable = false)
    @Builder.Default
    private boolean openForRedemption = true;

    /**
     * Minimum purchase amount in INR. SEBI requires AMCs to publish this.
     * Checked by MinimumPurchaseAmountValidator.
     * Default ₹1000 (most equity funds).
     */
    @Column(name = "minimum_purchase_amount", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal minimumPurchaseAmount = new BigDecimal("1000.00");

    /**
     * Lock-in end date for ELSS schemes (3-year mandatory lock-in from
     * fund inception or first purchase date — simplified here to a single date).
     * Null for non-ELSS schemes. Checked by LockInPeriodValidator.
     */
    @Column(name = "lock_in_end_date")
    private LocalDate lockInEndDate;
}
