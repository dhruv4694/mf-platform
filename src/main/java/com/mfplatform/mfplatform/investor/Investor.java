package com.mfplatform.mfplatform.investor;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Investor represents an individual investor in the fund house's books.
 *
 * Added kycComplete flag beyond the original design:
 * KYC (Know Your Customer) is a SEBI regulatory requirement. An investor
 * must complete KYC before they can transact. KycValidator checks this
 * at the start of both the purchase and redemption validation chains.
 *
 * In a real system, KYC status would be verified against CAMS/KFintech's
 * KYC registry. Here we simulate with a simple boolean flag.
 */
@Entity
@Table(name = "investor")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Investor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "pan_number", nullable = false, unique = true)
    private String panNumber;

    /**
     * Nullable: null = direct investment (no distributor).
     * Not null = investor is transacting through this distributor.
     * See ADR-008 and the distributor module for context.
     */
    @Column(name = "distributor_id")
    private Long distributorId;

    /**
     * Whether KYC has been completed for this investor.
     * Checked by KycValidator before any purchase or redemption.
     * Defaults to false — KYC must be explicitly completed (by ADMIN/ops)
     * before the investor can transact.
     */
    @Column(name = "kyc_complete", nullable = false)
    @Builder.Default
    private boolean kycComplete = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    /**
     * Marks KYC as complete. The only way to flip kycComplete — no setter,
     * same convention as Distributor.activate(). Called by KycVerificationWorker.
     */
    public void completeKyc() {
        this.kycComplete = true;
    }
}
