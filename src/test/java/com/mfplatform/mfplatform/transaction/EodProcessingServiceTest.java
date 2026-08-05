package com.mfplatform.mfplatform.transaction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for EodProcessingService — the orchestrator that loops over
 * PENDING transactions for a business date and aggregates outcomes.
 *
 * EodTransactionProcessor (the per-transaction settlement logic) is mocked
 * here so these tests focus purely on: finding the right rows, aggregating
 * counts correctly, and isolating one bad row's failure from the rest of the
 * batch. See EodTransactionProcessorTest for the actual settlement pipeline.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EodProcessingService")
class EodProcessingServiceTest {

    @Mock private MfTransactionRepository transactionRepository;
    @Mock private EodTransactionProcessor eodTransactionProcessor;

    @InjectMocks
    private EodProcessingService eodProcessingService;

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 7, 14);

    private MfTransaction txn(Long id) {
        return MfTransaction.builder()
                .id(id).folioId(1L).schemeId(1L)
                .type(TransactionType.PURCHASE)
                .status(TransactionStatus.PENDING)
                .requestAmount(new BigDecimal("5000.00"))
                .idempotencyKey("idem-" + id)
                .initiatedByUserId(1L)
                .initiatedByRole(InitiatedByRole.INVESTOR)
                .businessDate(BUSINESS_DATE)
                .build();
    }

    @Test
    @DisplayName("returns an all-zero summary when there are no PENDING transactions for the date")
    void noPendingTransactions() {
        when(transactionRepository.findByStatusAndBusinessDate(TransactionStatus.PENDING, BUSINESS_DATE))
                .thenReturn(List.of());

        EodProcessingService.EodSummary summary = eodProcessingService.runEod(BUSINESS_DATE);

        assertThat(summary.processed()).isZero();
        assertThat(summary.allotted()).isZero();
        assertThat(summary.failed()).isZero();
        assertThat(summary.pendingNoNav()).isZero();
        verifyNoInteractions(eodTransactionProcessor);
    }

    @Test
    @DisplayName("aggregates outcomes across a mixed batch: allotted, failed, pendingNoNav")
    void aggregatesMixedOutcomes() {
        MfTransaction t1 = txn(1L);
        MfTransaction t2 = txn(2L);
        MfTransaction t3 = txn(3L);
        when(transactionRepository.findByStatusAndBusinessDate(TransactionStatus.PENDING, BUSINESS_DATE))
                .thenReturn(List.of(t1, t2, t3));

        when(eodTransactionProcessor.processOne(1L, BUSINESS_DATE)).thenReturn(EodOutcome.ALLOTTED);
        when(eodTransactionProcessor.processOne(2L, BUSINESS_DATE)).thenReturn(EodOutcome.PENDING_NO_NAV);
        when(eodTransactionProcessor.processOne(3L, BUSINESS_DATE)).thenReturn(EodOutcome.FAILED);

        EodProcessingService.EodSummary summary = eodProcessingService.runEod(BUSINESS_DATE);

        assertThat(summary.processed()).isEqualTo(3);
        assertThat(summary.allotted()).isEqualTo(1);
        assertThat(summary.pendingNoNav()).isEqualTo(1);
        assertThat(summary.failed()).isEqualTo(1);
    }

    @Test
    @DisplayName("an unexpected exception from one row is counted as failed and doesn't stop the batch")
    void oneBadRowDoesNotAbortTheBatch() {
        MfTransaction t1 = txn(1L);
        MfTransaction t2 = txn(2L);
        when(transactionRepository.findByStatusAndBusinessDate(TransactionStatus.PENDING, BUSINESS_DATE))
                .thenReturn(List.of(t1, t2));

        when(eodTransactionProcessor.processOne(1L, BUSINESS_DATE))
                .thenThrow(new RuntimeException("simulated DB blip"));
        when(eodTransactionProcessor.processOne(2L, BUSINESS_DATE)).thenReturn(EodOutcome.ALLOTTED);

        EodProcessingService.EodSummary summary = eodProcessingService.runEod(BUSINESS_DATE);

        assertThat(summary.processed()).isEqualTo(2);
        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.allotted()).isEqualTo(1);
        verify(eodTransactionProcessor).processOne(eq(2L), eq(BUSINESS_DATE));
    }
}
