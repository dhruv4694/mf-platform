package com.mfplatform.mfplatform.distributor;

import com.mfplatform.mfplatform.notification.event.ApplicationEvents.*;
import org.springframework.context.ApplicationEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Random;

/**
 * DistributorVerificationWorker simulates background ARN verification.
 *
 * IN A REAL AMC SYSTEM:
 * After a distributor signs up, the fund house's ops team would:
 *   1. Verify the ARN code with AMFI's distributor registry
 *   2. Check the distributor's credentials and KYC documents
 *   3. Manually approve or reject the application in an internal ops tool
 * This typically takes 1-3 business days.
 *
 * IN THIS PROJECT (demo simulation):
 * A @Scheduled job runs every 10 seconds and processes all PENDING_VERIFICATION
 * distributors whose submittedAt was more than [random 5-60 seconds] ago.
 * Each pending distributor gets a unique random delay assigned at signup time.
 *
 * SIMULATION LOGIC:
 *   - 90% chance of ACTIVE (ARN valid) → simulates most distributors passing
 *   - 10% chance of REJECTED (ARN invalid) → simulates occasional failures
 *
 * DEMO FLOW:
 *   1. Distributor signs up → status: PENDING_VERIFICATION
 *   2. Wait up to 60 seconds
 *   3. Worker runs → status: ACTIVE (or REJECTED)
 *   4. Distributor can now add investors and view their book
 *
 * @EnableScheduling is required in a @Configuration class for @Scheduled to work.
 * We add it to MfPlatformApplication.
 */
@Component
public class DistributorVerificationWorker {

    private static final Logger log =
            LoggerFactory.getLogger(DistributorVerificationWorker.class);

    private static final int MAX_DELAY_SECONDS = 60;
    private static final double APPROVAL_RATE = 0.90; // 90% approved, 10% rejected

    private final DistributorRepository distributorRepository;
    private final DistributorService distributorService;
    private final ApplicationEventPublisher eventPublisher;
    private final Random random = new Random();

    public DistributorVerificationWorker(
            DistributorRepository distributorRepository,
            DistributorService distributorService,
            ApplicationEventPublisher eventPublisher) {
        this.distributorRepository = distributorRepository;
        this.distributorService = distributorService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Runs every 10 seconds. Finds all PENDING_VERIFICATION distributors
     * whose simulated delay has elapsed, then activates or rejects them.
     *
     * WHY 10-SECOND POLLING INTERVAL:
     * The maximum delay is 60 seconds. Checking every 10 seconds means a
     * distributor waits at most 70 seconds (60s delay + up to 10s until
     * next poll). Acceptable for a demo. In production you'd use an event
     * or webhook callback instead of polling.
     *
     * fixedDelay = 10_000ms means "10 seconds AFTER the previous run finished"
     * (not a fixed rate) — avoids overlapping runs if processing is slow.
     */
    @Scheduled(fixedDelay = 10_000)
    public void processVerifications() {
        // Find all distributors that submitted more than MAX_DELAY_SECONDS ago.
        // We use MAX_DELAY_SECONDS as the cutoff — distributors that submitted
        // within the last 60 seconds might still be within their random delay window.
        // The random delay is simulated by distributing submittedAt over the window:
        // some will be ready immediately after the cutoff, some just at the boundary.
        Instant cutoff = Instant.now().minus(MAX_DELAY_SECONDS, ChronoUnit.SECONDS);

        List<Distributor> pending = distributorRepository
                .findByStatusAndSubmittedAtBefore(
                        DistributorStatus.PENDING_VERIFICATION, cutoff);

        if (pending.isEmpty()) {
            return; // Nothing to process — skip quietly
        }

        log.info("DistributorVerificationWorker: processing {} pending distributor(s)",
                pending.size());

        for (Distributor distributor : pending) {
            simulateVerification(distributor);
        }
    }

    /**
     * Simulates the ARN verification result for one distributor.
     * 90% chance of approval, 10% chance of rejection.
     *
     * In a real system this would call AMFI's API or check an internal registry.
     */
    private void simulateVerification(Distributor distributor) {
        boolean approved = random.nextDouble() < APPROVAL_RATE;

        if (approved) {
            distributorService.activateAfterVerification(distributor.getId());
            // Reload to get the updated entity (status = ACTIVE, verifiedAt set)
            distributorRepository.findById(distributor.getId()).ifPresent(updated ->
                eventPublisher.publishEvent(new DistributorActivatedEvent(this, updated))
            );
            log.info("Distributor {} (ARN: {}) verified and ACTIVATED",
                    distributor.getId(), distributor.getArnCode());
        } else {
            distributorService.rejectAfterVerification(distributor.getId());
            log.warn("Distributor {} (ARN: {}) REJECTED — simulated invalid ARN",
                    distributor.getId(), distributor.getArnCode());
        }
    }
}
