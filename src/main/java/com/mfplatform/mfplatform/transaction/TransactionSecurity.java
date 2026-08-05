package com.mfplatform.mfplatform.transaction;

import com.mfplatform.mfplatform.common.OwnershipValidator;
import com.mfplatform.mfplatform.common.Role;
import com.mfplatform.mfplatform.security.ActorContext;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * TransactionSecurity backs the @PreAuthorize expression on GET /transactions/{id}.
 *
 * A transaction doesn't have a direct owner — it belongs to a folio, which
 * belongs to an investor. So "can this caller view transaction X" means
 * "can this caller view the folio that transaction X belongs to."
 *
 * This is a two-hop check: transaction → folio → ownership rule.
 * OwnershipValidator provides the folio-level check; we add the transaction
 * → folio lookup on top.
 */
@Component("transactionSecurity")
public class TransactionSecurity {

    private final MfTransactionRepository transactionRepository;
    private final OwnershipValidator ownershipValidator;

    public TransactionSecurity(
            MfTransactionRepository transactionRepository,
            OwnershipValidator ownershipValidator) {
        this.transactionRepository = transactionRepository;
        this.ownershipValidator = ownershipValidator;
    }

    /**
     * Can this caller view the transaction with the given id?
     *
     * ADMIN       → always yes
     * INVESTOR    → only if the transaction's folio belongs to them
     * DISTRIBUTOR → only if the transaction's folio belongs to one of their clients
     */
    public boolean canView(Long transactionId, Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof ActorContext actor)) {
            return false;
        }

        if (actor.role() == Role.ADMIN) return true;

        return transactionRepository.findById(transactionId)
                .map(txn -> switch (actor.role()) {
                    case ADMIN -> true; // handled above
                    case INVESTOR ->
                        ownershipValidator.isInvestorFolio(txn.getFolioId(), actor.investorId());
                    case DISTRIBUTOR ->
                        ownershipValidator.isDistributorFolio(txn.getFolioId(), actor.distributorId());
                })
                .orElse(false);
    }
}
