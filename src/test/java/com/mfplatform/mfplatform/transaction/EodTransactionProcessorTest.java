package com.mfplatform.mfplatform.transaction;

import com.mfplatform.mfplatform.nav.NavHistory;
import com.mfplatform.mfplatform.nav.NavHistoryRepository;
import com.mfplatform.mfplatform.scheme.Scheme;
import com.mfplatform.mfplatform.scheme.SchemeCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for EodTransactionProcessor — settles exactly one PENDING
 * transaction per call.
 *
 * KEY BEHAVIORS UNDER TEST:
 *   1. No NAV for (scheme, businessDate) → transaction is left completely
 *      untouched (still PENDING) and PENDING_NO_NAV is returned — this is
 *      what makes EOD reruns idempotent without a reverse state transition.
 *   2. NAV present → the NavHistory row found by the exact-match lookup is
 *      applied directly (no NavEligibilityService involved).
 *   3. PURCHASE runs payment simulation; REDEMPTION transitions straight to
 *      PAYMENT_REALIZED (mirroring the old RedemptionService stand-in).
 *   4. AllotmentResult outcomes map to the right EodOutcome.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EodTransactionProcessor")
class EodTransactionProcessorTest {

    @Mock private MfTransactionRepository transactionRepository;
    @Mock private NavHistoryRepository navHistoryRepository;
    @Mock private PaymentSimulationService paymentSimulationService;
    @Mock private UnitAllotmentService unitAllotmentService;

    @InjectMocks
    private EodTransactionProcessor eodTransactionProcessor;

    private static final Long TXN_ID = 100L;
    private static final Long SCHEME_ID = 1L;
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 7, 14);

    private Scheme scheme;
    private NavHistory nav;
    private MfTransaction pendingPurchase;

    @BeforeEach
    void setUp() {
        scheme = Scheme.builder()
                .id(SCHEME_ID).schemeName("Bluechip Equity Growth Fund")
                .schemeCode("BCEG001").category(SchemeCategory.EQUITY)
                .build();

        nav = NavHistory.builder()
                .id(5L).scheme(scheme).navDate(BUSINESS_DATE)
                .navValue(new BigDecimal("48.2345")).build();

        pendingPurchase = MfTransaction.builder()
                .id(TXN_ID).folioId(42L).schemeId(SCHEME_ID)
                .type(TransactionType.PURCHASE)
                .status(TransactionStatus.PENDING)
                .requestAmount(new BigDecimal("5000.00"))
                .idempotencyKey("idem-001")
                .initiatedByUserId(10L)
                .initiatedByRole(InitiatedByRole.INVESTOR)
                .businessDate(BUSINESS_DATE)
                .build();
    }

    @Test
    @DisplayName("no NAV for (scheme, businessDate) — transaction untouched, returns PENDING_NO_NAV")
    void noNavLeavesTransactionUntouched() {
        when(transactionRepository.findById(TXN_ID)).thenReturn(Optional.of(pendingPurchase));
        when(navHistoryRepository.findBySchemeIdAndNavDate(SCHEME_ID, BUSINESS_DATE))
                .thenReturn(Optional.empty());

        EodOutcome outcome = eodTransactionProcessor.processOne(TXN_ID, BUSINESS_DATE);

        assertThat(outcome).isEqualTo(EodOutcome.PENDING_NO_NAV);
        verifyNoInteractions(paymentSimulationService, unitAllotmentService);
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("PURCHASE with NAV present: simulates payment, applies the exact-match NAV, allots")
    void purchaseSettlesWhenNavPresent() {
        when(transactionRepository.findById(TXN_ID)).thenReturn(Optional.of(pendingPurchase));
        when(navHistoryRepository.findBySchemeIdAndNavDate(SCHEME_ID, BUSINESS_DATE))
                .thenReturn(Optional.of(nav));
        // PaymentSimulationService is mocked out here, so — same as the real
        // implementation does internally — simulate its PENDING -> PAYMENT_REALIZED
        // side effect on the transaction; otherwise applyNav() below would try
        // to jump straight from PENDING to NAV_APPLIED and the state machine
        // would (correctly) reject it.
        when(paymentSimulationService.simulateInstantRealization(TXN_ID)).thenAnswer(inv -> {
            pendingPurchase.transitionTo(TransactionStatus.PAYMENT_REALIZED);
            return null;
        });
        when(unitAllotmentService.allot(TXN_ID))
                .thenReturn(new AllotmentResult.Success(new BigDecimal("103.6765"), nav.getNavValue()));

        EodOutcome outcome = eodTransactionProcessor.processOne(TXN_ID, BUSINESS_DATE);

        assertThat(outcome).isEqualTo(EodOutcome.ALLOTTED);
        verify(paymentSimulationService).initiatePayment(TXN_ID);
        verify(paymentSimulationService).simulateInstantRealization(TXN_ID);
        verify(unitAllotmentService).allot(TXN_ID);

        // applyNav was called with the exact NAV row found by the pre-check —
        // no NavEligibilityService anywhere in this pipeline.
        assertThat(pendingPurchase.getApplicableNav()).isEqualTo(nav);
    }

    @Test
    @DisplayName("REDEMPTION with NAV present: skips payment simulation, transitions straight to PAYMENT_REALIZED")
    void redemptionSkipsPaymentSimulation() {
        MfTransaction pendingRedemption = MfTransaction.builder()
                .id(TXN_ID).folioId(42L).schemeId(SCHEME_ID)
                .type(TransactionType.REDEMPTION)
                .status(TransactionStatus.PENDING)
                .requestUnits(new BigDecimal("50.0000"))
                .idempotencyKey("idem-002")
                .initiatedByUserId(10L)
                .initiatedByRole(InitiatedByRole.INVESTOR)
                .businessDate(BUSINESS_DATE)
                .build();

        when(transactionRepository.findById(TXN_ID)).thenReturn(Optional.of(pendingRedemption));
        when(navHistoryRepository.findBySchemeIdAndNavDate(SCHEME_ID, BUSINESS_DATE))
                .thenReturn(Optional.of(nav));
        when(unitAllotmentService.allot(TXN_ID))
                .thenReturn(new AllotmentResult.Success(new BigDecimal("50.0000"), nav.getNavValue()));

        EodOutcome outcome = eodTransactionProcessor.processOne(TXN_ID, BUSINESS_DATE);

        assertThat(outcome).isEqualTo(EodOutcome.ALLOTTED);
        verifyNoInteractions(paymentSimulationService);
        assertThat(pendingRedemption.getStatus()).isNotEqualTo(TransactionStatus.PENDING);
    }

    @Test
    @DisplayName("allotment failure maps to EodOutcome.FAILED")
    void allotmentFailureMapsToFailed() {
        when(transactionRepository.findById(TXN_ID)).thenReturn(Optional.of(pendingPurchase));
        when(navHistoryRepository.findBySchemeIdAndNavDate(SCHEME_ID, BUSINESS_DATE))
                .thenReturn(Optional.of(nav));
        when(paymentSimulationService.simulateInstantRealization(TXN_ID)).thenAnswer(inv -> {
            pendingPurchase.transitionTo(TransactionStatus.PAYMENT_REALIZED);
            return null;
        });
        when(unitAllotmentService.allot(TXN_ID))
                .thenReturn(new AllotmentResult.Failed("Holding update failed after 3 retries"));

        EodOutcome outcome = eodTransactionProcessor.processOne(TXN_ID, BUSINESS_DATE);

        assertThat(outcome).isEqualTo(EodOutcome.FAILED);
    }
}
