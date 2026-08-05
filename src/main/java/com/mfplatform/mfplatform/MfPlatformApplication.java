package com.mfplatform.mfplatform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Main entry point for the Mutual Fund Platform.
 *
 * @EnableScheduling activates @Scheduled methods:
 *   - SipBatchJobLauncher   (daily SIP installment batch trigger)
 *   - DistributorVerificationWorker (background ARN verification)
 *   - KycVerificationWorker (background KYC auto-verification)
 *
 * @EnableAsync activates @Async methods:
 *   - NotificationService event listeners (email/SMS sent off the request thread)
 *   Without this, @Async is silently ignored and methods run synchronously.
 *   Thread pool is configured in AsyncConfig.
 */
@SpringBootApplication
@EnableScheduling
@EnableAsync
public class MfPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(MfPlatformApplication.class, args);
    }
}
