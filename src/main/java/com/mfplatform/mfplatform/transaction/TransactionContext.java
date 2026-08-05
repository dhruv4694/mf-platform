package com.mfplatform.mfplatform.transaction;

import com.mfplatform.mfplatform.folio.Folio;
import com.mfplatform.mfplatform.investor.Investor;
import com.mfplatform.mfplatform.scheme.Scheme;
import com.mfplatform.mfplatform.security.ActorContext;

import java.math.BigDecimal;

/**
 * TransactionContext carries all the data validators need — built once
 * before the validation chain runs, then passed through every validator.
 *
 * WHY A CONTEXT OBJECT:
 * Without this, each validator would need to load its own data from the DB:
 *   KycValidator loads Investor to check KYC
 *   FolioOwnershipValidator loads Folio to check investor_id
 *   SufficientUnitsValidator loads Holding to check units
 *   SchemeOpenValidator loads Scheme to check category/status
 *
 * With TransactionContext, the caller (PurchaseService/RedemptionService)
 * loads all needed data once, wraps it in a context, and the chain validators
 * read from the context — zero additional DB queries during validation.
 *
 * This is a lightweight version of the "Parameter Object" refactoring pattern
 * combined with the "Context Object" pattern used in frameworks like Spring MVC.
 *
 * Using a record — immutable, all fields set at construction, no setters.
 * The context describes the state of the world at the moment the transaction
 * was requested; it should not be mutated during validation.
 */
public record TransactionContext(

        /**
         * The investor who owns the folio.
         * Used by KycValidator and for notification lookups.
         */
        Investor investor,

        /**
         * The folio the transaction is being made against.
         * Used by FolioOwnershipValidator.
         */
        Folio folio,

        /**
         * The scheme being purchased or redeemed.
         * Used by SchemeOpenForPurchaseValidator, SchemeOpenForRedemptionValidator,
         * MinimumPurchaseAmountValidator.
         */
        Scheme scheme,

        /**
         * The actor making the request — investor, distributor, or admin.
         * Used by FolioOwnershipValidator to verify the right person is transacting.
         */
        ActorContext actor,

        /**
         * The amount for purchase transactions (null for redemption-by-units).
         */
        BigDecimal requestAmount,

        /**
         * The units for redemption transactions (null for purchase).
         */
        BigDecimal requestUnits,

        /**
         * Current unit balance for this folio/scheme pair.
         * Used by SufficientUnitsValidator for redemption checks.
         * May be BigDecimal.ZERO for a new holding (first-time purchase).
         */
        BigDecimal currentUnitsHeld

) {}
