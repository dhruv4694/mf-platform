package com.mfplatform.mfplatform.investor;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InvestorRepository extends JpaRepository<Investor, Long> {

    Page<Investor> findByDistributorId(Long distributorId, Pageable pageable);

    Optional<Investor> findByEmail(String email);

    Optional<Investor> findByPanNumber(String panNumber);

    /**
     * Used by KycVerificationWorker to find investors still awaiting KYC.
     */
    List<Investor> findByKycComplete(boolean kycComplete);
}
