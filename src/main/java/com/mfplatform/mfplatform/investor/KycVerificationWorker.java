package com.mfplatform.mfplatform.investor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * KycVerificationWorker simulates background KYC verification, mirroring
 * DistributorVerificationWorker's role for distributor ARN verification.
 *
 * IN A REAL AMC SYSTEM:
 * KYC would be verified against CAMS/KFintech's KYC registry, an external
 * check that takes time and can fail.
 *
 * IN THIS PROJECT (demo simulation):
 * A @Scheduled job runs every 180 seconds (3 minutes) and marks every investor
 * still awaiting KYC as verified. No approve/reject branch (unlike distributor
 * verification) and no simulated delay — KycValidator in the purchase/
 * redemption validation chains just needs kycComplete to eventually become
 * true so newly signed-up investors aren't permanently blocked from
 * transacting in a demo session.
 */
@Component
public class KycVerificationWorker {

    private static final Logger log = LoggerFactory.getLogger(KycVerificationWorker.class);

    private final InvestorRepository investorRepository;

    public KycVerificationWorker(InvestorRepository investorRepository) {
        this.investorRepository = investorRepository;
    }

    /**
     * Runs every 180 seconds (3 minutes). Marks every investor with
     * kycComplete = false as verified.
     */
    @Scheduled(fixedDelay = 180_000)
    @Transactional
    public void processKycVerifications() {
        List<Investor> pending = investorRepository.findByKycComplete(false);

        if (pending.isEmpty()) {
            return;
        }

        for (Investor investor : pending) {
            investor.completeKyc();
            investorRepository.save(investor);
            log.info("KYC verified for investor {} ({})", investor.getId(), investor.getEmail());
        }
    }
}
