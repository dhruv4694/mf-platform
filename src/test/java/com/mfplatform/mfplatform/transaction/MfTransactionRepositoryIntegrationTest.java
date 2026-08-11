package com.mfplatform.mfplatform.transaction;

import com.mfplatform.mfplatform.BaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for MfTransactionRepository's listing-query ordering.
 *
 * Tests real SQL against a real PostgreSQL 16 database (Testcontainers +
 * Flyway) — the thing under test is genuinely an ORDER BY clause, which a
 * mocked repository can't verify.
 *
 * WHAT WE'RE TESTING:
 * findByFolioIdInOrderByBusinessDateDesc() sorts by businessDate, not
 * creation/insertion order. This matters because BusinessDateService.advance()
 * deliberately permits moving the business date backward — a transaction
 * created later in real time can have an earlier businessDate than one
 * created before it, and the listing must reflect businessDate order
 * regardless of which row was actually inserted first.
 */
@DisplayName("MfTransactionRepository (Integration)")
@Disabled("Testcontainers can't reach a Docker daemon in the current environment " +
        "(Windows Docker Desktop socket isn't reachable from a nested container the way " +
        "this suite is currently being run) - Previous attempts to find a Docker environment failed. " +
        "Re-enable once run directly on a host with working Testcontainers/Docker access.")
class MfTransactionRepositoryIntegrationTest extends BaseIntegrationTest {

    @Autowired private MfTransactionRepository transactionRepository;

    private static final Long FOLIO_ID = 1L;
    private static final Long SCHEME_ID = 1L;

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();
    }

    @Test
    @DisplayName("orders by businessDate descending, not insertion order")
    void ordersByBusinessDateDescendingRegardlessOfInsertionOrder() {
        // Insert the EARLIER-dated transaction FIRST, in real time, simulating
        // what happens when the business date is moved backward between two
        // creations — insertion order is deliberately the opposite of the
        // expected display order.
        MfTransaction earlierBusinessDate = transactionRepository.save(
                buildTransaction(LocalDate.of(2026, 8, 9), "idem-aug9"));
        MfTransaction laterBusinessDate = transactionRepository.save(
                buildTransaction(LocalDate.of(2026, 9, 10), "idem-sep10"));

        Page<MfTransaction> result = transactionRepository
                .findByFolioIdInOrderByBusinessDateDesc(List.of(FOLIO_ID), PageRequest.of(0, 10));

        assertThat(result.getContent())
                .extracting(MfTransaction::getId)
                .containsExactly(laterBusinessDate.getId(), earlierBusinessDate.getId());
    }

    private MfTransaction buildTransaction(LocalDate businessDate, String idempotencyKey) {
        return MfTransaction.builder()
                .folioId(FOLIO_ID)
                .schemeId(SCHEME_ID)
                .type(TransactionType.PURCHASE)
                .status(TransactionStatus.PENDING)
                .requestAmount(new java.math.BigDecimal("1000.00"))
                .idempotencyKey(idempotencyKey)
                .initiatedByUserId(1L)
                .initiatedByRole(InitiatedByRole.ADMIN)
                .requestedAt(Instant.now())
                .businessDate(businessDate)
                .build();
    }
}
