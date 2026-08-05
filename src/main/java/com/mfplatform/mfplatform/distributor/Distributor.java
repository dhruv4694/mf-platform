package com.mfplatform.mfplatform.distributor;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Distributor represents an AMFI-registered mutual fund distributor.
 *
 * STATUS LIFECYCLE (see DistributorStatus for full docs):
 *   PENDING_VERIFICATION → ACTIVE     (background verification worker)
 *   PENDING_VERIFICATION → REJECTED   (background verification worker)
 *   ACTIVE               → SUSPENDED  (ADMIN action)
 *   SUSPENDED            → ACTIVE     (ADMIN action — reinstatement)
 *
 * The status field drives what a distributor can do — not their ability to
 * log in (Option A: credentials always work, service layer enforces status).
 *
 * submittedAt: when the signup was submitted, used by the background
 * verification worker to calculate when the random delay has elapsed.
 */
@Entity
@Table(name = "distributor")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Distributor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "arn_code", nullable = false, unique = true)
    private String arnCode;

    @Column(nullable = false, unique = true)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private DistributorStatus status = DistributorStatus.PENDING_VERIFICATION;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant submittedAt = Instant.now();

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    // ─── Domain methods ───────────────────────────────────────────────────────

    public void activate() {
        this.status = DistributorStatus.ACTIVE;
        this.verifiedAt = Instant.now();
    }

    public void reject() {
        this.status = DistributorStatus.REJECTED;
        this.verifiedAt = Instant.now();
    }

    public void suspend() {
        this.status = DistributorStatus.SUSPENDED;
    }

    public boolean isActive() {
        return this.status == DistributorStatus.ACTIVE;
    }

    public boolean isPendingVerification() {
        return this.status == DistributorStatus.PENDING_VERIFICATION;
    }
}
