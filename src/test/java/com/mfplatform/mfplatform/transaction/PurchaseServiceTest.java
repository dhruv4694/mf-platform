package com.mfplatform.mfplatform.transaction;

import com.mfplatform.mfplatform.common.BusinessDateService;
import com.mfplatform.mfplatform.common.Role;
import com.mfplatform.mfplatform.folio.Folio;
import com.mfplatform.mfplatform.folio.FolioRepository;
import com.mfplatform.mfplatform.investor.Investor;
import com.mfplatform.mfplatform.investor.InvestorRepository;
import com.mfplatform.mfplatform.scheme.Scheme;
import com.mfplatform.mfplatform.scheme.SchemeCategory;
import com.mfplatform.mfplatform.scheme.SchemeRepository;
import com.mfplatform.mfplatform.security.ActorContext;
import com.mfplatform.mfplatform.transaction.dto.TransactionDtos.*;
import com.mfplatform.mfplatform.transaction.validation.PurchaseValidationChain;
import com.mfplatform.mfplatform.transaction.validation.TransactionValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for PurchaseService.
 *
 * WHAT WE'RE TESTING:
 * PurchaseService now only creates the PENDING transaction row — payment,
 * NAV, and allotment moved to EodProcessingService (see EodProcessingServiceTest
 * for that pipeline). So the scope here is:
 *   1. Validation chain runs before any DB write
 *   2. If validation fails, no transaction is created
 *   3. The transaction is created with PENDING status, stamped with the
 *      current business date
 *   4. The returned DTO reflects the PENDING state
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PurchaseService")
class PurchaseServiceTest {

    @Mock private MfTransactionRepository   transactionRepository;
    @Mock private InvestorRepository        investorRepository;
    @Mock private FolioRepository           folioRepository;
    @Mock private SchemeRepository          schemeRepository;
    @Mock private HoldingService            holdingService;
    @Mock private PurchaseValidationChain   validationChain;
    @Mock private BusinessDateService       businessDateService;

    @InjectMocks
    private PurchaseService purchaseService;

    // ─── Shared test fixtures ─────────────────────────────────────────────────

    private static final Long INVESTOR_ID = 7L;
    private static final Long FOLIO_ID    = 42L;
    private static final Long SCHEME_ID   = 1L;
    private static final Long TXN_ID      = 100L;
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 7, 14);

    private Investor     investor;
    private Folio        folio;
    private Scheme       scheme;
    private PurchaseRequest request;
    private ActorContext actorContext;
    private MfTransaction savedTransaction;

    @BeforeEach
    void setUp() {
        investor = Investor.builder()
                .id(INVESTOR_ID).name("Priya Sharma").email("priya@example.com")
                .panNumber("ABCPS1234F").kycComplete(true).build();

        folio = Folio.builder()
                .id(FOLIO_ID).folioNumber("FL3A2B1C9D").investorId(INVESTOR_ID).build();

        scheme = Scheme.builder()
                .id(SCHEME_ID).schemeName("Bluechip Equity Growth Fund")
                .schemeCode("BCEG001").category(SchemeCategory.EQUITY)
                .openForPurchase(true).minimumPurchaseAmount(new BigDecimal("1000"))
                .build();

        request = new PurchaseRequest(
                FOLIO_ID, SCHEME_ID, new BigDecimal("5000.00"), "idem-key-001");

        actorContext = new ActorContext(10L, Role.INVESTOR, INVESTOR_ID, null);

        // The transaction entity as it would be returned by repository.save()
        savedTransaction = MfTransaction.builder()
                .id(TXN_ID).folioId(FOLIO_ID).schemeId(SCHEME_ID)
                .type(TransactionType.PURCHASE)
                .status(TransactionStatus.PENDING)
                .requestAmount(new BigDecimal("5000.00"))
                .idempotencyKey("idem-key-001")
                .initiatedByUserId(10L)
                .initiatedByRole(InitiatedByRole.INVESTOR)
                .businessDate(BUSINESS_DATE)
                .build();
    }

    // ─── Happy path ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("happy path")
    class HappyPathTests {

        @BeforeEach
        void stubHappyPath() {
            when(folioRepository.findById(FOLIO_ID)).thenReturn(Optional.of(folio));
            when(investorRepository.findById(INVESTOR_ID)).thenReturn(Optional.of(investor));
            when(schemeRepository.findById(SCHEME_ID)).thenReturn(Optional.of(scheme));
            when(holdingService.getCurrentUnits(FOLIO_ID, SCHEME_ID))
                    .thenReturn(BigDecimal.ZERO);
            when(businessDateService.today()).thenReturn(BUSINESS_DATE);
            // validationChain.validate() does nothing by default (void return)
            when(transactionRepository.save(any())).thenReturn(savedTransaction);
        }

        @Test
        @DisplayName("returns a TransactionResponse with PENDING status")
        void returnsPendingResponse() {
            TransactionResponse response = purchaseService.createPurchase(request, actorContext);

            assertThat(response).isNotNull();
            assertThat(response.status()).isEqualTo("PENDING");
            assertThat(response.type()).isEqualTo("PURCHASE");
            assertThat(response.requestAmount()).isEqualByComparingTo(new BigDecimal("5000.00"));
            assertThat(response.businessDate()).isEqualTo(BUSINESS_DATE);
        }

        @Test
        @DisplayName("calls validation chain before saving the transaction")
        void callsValidationChainFirst() {
            purchaseService.createPurchase(request, actorContext);

            var inOrder = inOrder(validationChain, transactionRepository);
            inOrder.verify(validationChain).validate(any());
            inOrder.verify(transactionRepository).save(any());
        }

        @Test
        @DisplayName("creates the transaction with PENDING status, PURCHASE type, and the current business date")
        void createsTransactionWithPendingStatus() {
            purchaseService.createPurchase(request, actorContext);

            ArgumentCaptor<MfTransaction> txnCaptor =
                    ArgumentCaptor.forClass(MfTransaction.class);
            verify(transactionRepository).save(txnCaptor.capture());

            MfTransaction saved = txnCaptor.getValue();
            assertThat(saved.getStatus()).isEqualTo(TransactionStatus.PENDING);
            assertThat(saved.getType()).isEqualTo(TransactionType.PURCHASE);
            assertThat(saved.getFolioId()).isEqualTo(FOLIO_ID);
            assertThat(saved.getSchemeId()).isEqualTo(SCHEME_ID);
            assertThat(saved.getBusinessDate()).isEqualTo(BUSINESS_DATE);
            assertThat(saved.getRequestAmount())
                    .isEqualByComparingTo(new BigDecimal("5000.00"));
        }

        @Test
        @DisplayName("records the correct initiatedByRole from the actor context")
        void recordsInitiatedByRole() {
            purchaseService.createPurchase(request, actorContext);

            ArgumentCaptor<MfTransaction> captor = ArgumentCaptor.forClass(MfTransaction.class);
            verify(transactionRepository).save(captor.capture());

            MfTransaction saved = captor.getValue();
            assertThat(saved.getInitiatedByRole()).isEqualTo(InitiatedByRole.INVESTOR);
            assertThat(saved.getInitiatedByUserId()).isEqualTo(10L);
        }

        @Test
        @DisplayName("does not attempt any settlement — no payment, NAV, or allotment interaction")
        void doesNotSettle() {
            purchaseService.createPurchase(request, actorContext);

            // PurchaseService no longer depends on PaymentSimulationService,
            // NavEligibilityService, or UnitAllotmentService at all — settlement
            // is EodProcessingService's job now. Nothing to verify "never called"
            // on since those collaborators aren't even wired into this service
            // anymore; this test documents that the transaction is only saved once.
            verify(transactionRepository, times(1)).save(any());
        }
    }

    // ─── Validation failure ───────────────────────────────────────────────────

    @Nested
    @DisplayName("when validation fails")
    class ValidationFailureTests {

        @BeforeEach
        void stubValidationFailure() {
            when(folioRepository.findById(FOLIO_ID)).thenReturn(Optional.of(folio));
            when(investorRepository.findById(INVESTOR_ID)).thenReturn(Optional.of(investor));
            when(schemeRepository.findById(SCHEME_ID)).thenReturn(Optional.of(scheme));
            when(holdingService.getCurrentUnits(FOLIO_ID, SCHEME_ID))
                    .thenReturn(BigDecimal.ZERO);

            // KYC validation fails
            doThrow(new TransactionValidationException("KYC not complete"))
                    .when(validationChain).validate(any());
        }

        @Test
        @DisplayName("propagates TransactionValidationException to the caller")
        void propagatesValidationException() {
            assertThatThrownBy(() ->
                purchaseService.createPurchase(request, actorContext)
            )
            .isInstanceOf(TransactionValidationException.class)
            .hasMessageContaining("KYC");
        }

        @Test
        @DisplayName("does NOT save a transaction when validation fails")
        void doesNotSaveTransactionOnValidationFailure() {
            catchThrowable(() -> purchaseService.createPurchase(request, actorContext));

            verify(transactionRepository, never()).save(any());
        }
    }
}
