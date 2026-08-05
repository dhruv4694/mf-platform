package com.mfplatform.mfplatform.transaction;

/**
 * InitiatedByRole is a denormalized snapshot of the actor's role at the time
 * a transaction was created.
 *
 * WHY DENORMALIZE THIS:
 * We could derive "who initiated this" by joining mf_transaction → user_account
 * and reading user_account.role. But what if that user_account is later
 * deactivated, or the distributor's role changes? The historical record would
 * become inaccurate.
 *
 * Storing the role at transaction creation time means the audit trail is
 * self-describing and immutable — "this transaction was placed by a DISTRIBUTOR"
 * is permanently recorded, regardless of what happens to that account later.
 *
 * This is a common pattern in financial/audit systems: denormalize facts that
 * must remain stable over time, even if the source data changes.
 *
 * Separate from common.Role because the semantics are subtly different:
 * common.Role describes a user_account's current role.
 * InitiatedByRole describes who acted at a specific point in time.
 */
public enum InitiatedByRole {
    INVESTOR,
    DISTRIBUTOR,
    ADMIN
}
