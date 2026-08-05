package com.mfplatform.mfplatform.distributor;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DistributorRepository extends JpaRepository<Distributor, Long> {

    Optional<Distributor> findByArnCode(String arnCode);

    Optional<Distributor> findByEmail(String email);

    Page<Distributor> findByStatus(DistributorStatus status, Pageable pageable);

    /**
     * Finds all distributors in PENDING_VERIFICATION status whose submittedAt
     * is before the given cutoff time.
     *
     * Used by DistributorVerificationWorker to find distributors whose
     * simulated verification delay has elapsed.
     *
     * Example: cutoff = now - 60s → finds all pending distributors that
     * signed up more than 60 seconds ago.
     */
    List<Distributor> findByStatusAndSubmittedAtBefore(
            DistributorStatus status, Instant cutoff);
}
