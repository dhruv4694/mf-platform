package com.mfplatform.mfplatform.distributor;

/**
 * DistributorStatus tracks the distributor's onboarding lifecycle.
 *
 * PENDING_VERIFICATION — just signed up via public endpoint, awaiting
 *                        simulated ARN verification. Can log in but cannot
 *                        add investors or view client book.
 *
 * ACTIVE              — ARN verified, full access granted.
 *
 * REJECTED            — ARN verification failed (e.g. invalid/expired ARN).
 *                        Account exists but no actions permitted.
 *
 * SUSPENDED           — Was active, suspended by ADMIN (e.g. AMFI revoked ARN).
 *                        Stricter than REJECTED — implies prior legitimacy.
 *
 * STATUS CHECK ENFORCEMENT:
 * Status is NOT enforced at the JWT/login layer (Option A decision — see
 * design notes). Instead, DistributorService checks status before any
 * meaningful operation. This keeps the auth layer simple and mirrors how
 * investor KYC will be enforced (same pattern, same layer).
 */
public enum DistributorStatus {
    PENDING_VERIFICATION,
    ACTIVE,
    REJECTED,
    SUSPENDED
}
