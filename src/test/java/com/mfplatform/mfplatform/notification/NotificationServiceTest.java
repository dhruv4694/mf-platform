package com.mfplatform.mfplatform.notification;

import com.mfplatform.mfplatform.folio.Folio;
import com.mfplatform.mfplatform.folio.FolioRepository;
import com.mfplatform.mfplatform.investor.Investor;
import com.mfplatform.mfplatform.investor.InvestorRepository;
import com.mfplatform.mfplatform.notification.channel.NotificationChannel;
import com.mfplatform.mfplatform.notification.dto.NotificationPayload;
import com.mfplatform.mfplatform.notification.event.ApplicationEvents.*;
import com.mfplatform.mfplatform.scheme.Scheme;
import com.mfplatform.mfplatform.scheme.SchemeCategory;
import com.mfplatform.mfplatform.scheme.SchemeRepository;
import com.mfplatform.mfplatform.sip.SipFrequency;
import com.mfplatform.mfplatform.sip.SipMandate;
import com.mfplatform.mfplatform.sip.SipMandateRepository;
import com.mfplatform.mfplatform.sip.SipMandateStatus;
import com.mfplatform.mfplatform.transaction.InitiatedByRole;
import com.mfplatform.mfplatform.transaction.MfTransaction;
import com.mfplatform.mfplatform.transaction.TransactionStatus;
import com.mfplatform.mfplatform.transaction.TransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for NotificationService's three transaction/SIP-related
 * listeners — onTransactionCreated, onTransactionSettled, onSipMandateCreated.
 *
 * WHAT WE'RE TESTING:
 * Each listener resolves folio/investor/scheme (and SIP mandate reference,
 * where relevant) via the injected repositories, builds a NotificationPayload
 * via the corresponding factory method, and dispatches it through every
 * enabled channel — same dispatch mechanics already covered implicitly by
 * the existing investor-welcome/distributor listeners, so these tests focus
 * on the NEW resolution logic (folio/investor/scheme/mandate lookups) and
 * that the correct event/payload flows through to the channel.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationService — transaction & SIP listeners")
class NotificationServiceTest {

    @Mock private NotificationChannel   emailChannel;
    @Mock private NotificationRepository notificationRepository;
    @Mock private FolioRepository       folioRepository;
    @Mock private InvestorRepository    investorRepository;
    @Mock private SchemeRepository      schemeRepository;
    @Mock private SipMandateRepository  sipMandateRepository;

    private NotificationService notificationService;

    private static final Long INVESTOR_ID = 7L;
    private static final Long FOLIO_ID    = 42L;
    private static final Long SCHEME_ID   = 1L;
    private static final Long TXN_ID      = 100L;
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 8, 9);

    private Investor investor;
    private Folio    folio;
    private Scheme   scheme;

    @BeforeEach
    void setUp() {
        notificationService = new NotificationService(
                List.of(emailChannel), notificationRepository,
                folioRepository, investorRepository, schemeRepository, sipMandateRepository);

        lenient().when(emailChannel.isEnabled()).thenReturn(true);
        lenient().when(emailChannel.channelName()).thenReturn("EMAIL");

        investor = Investor.builder()
                .id(INVESTOR_ID).name("Priya Sharma").email("priya@example.com")
                .panNumber("ABCPS1234F").kycComplete(true).build();

        folio = Folio.builder()
                .id(FOLIO_ID).folioNumber("FL3A2B1C9D").investorId(INVESTOR_ID).build();

        scheme = Scheme.builder()
                .id(SCHEME_ID).schemeName("Bluechip Equity Growth Fund")
                .schemeCode("BCEG001").category(SchemeCategory.EQUITY).build();

        lenient().when(folioRepository.findById(FOLIO_ID)).thenReturn(Optional.of(folio));
        lenient().when(investorRepository.findById(INVESTOR_ID)).thenReturn(Optional.of(investor));
        lenient().when(schemeRepository.findById(SCHEME_ID)).thenReturn(Optional.of(scheme));
    }

    // ─── onTransactionCreated ────────────────────────────────────────────────

    @Test
    @DisplayName("onTransactionCreated: manual purchase dispatches with the non-SIP subject")
    void manualPurchaseCreatedDispatchesNonSipSubject() {
        MfTransaction transaction = manualPurchase();

        notificationService.onTransactionCreated(new TransactionCreatedEvent(this, transaction));

        ArgumentCaptor<NotificationPayload> captor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(emailChannel).send(captor.capture());
        assertThat(captor.getValue().subject()).isEqualTo("Purchase request received");
        verifyNoInteractions(sipMandateRepository);
    }

    @Test
    @DisplayName("onTransactionCreated: SIP-originated purchase resolves the mandate reference and uses the SIP subject")
    void sipPurchaseCreatedResolvesMandateReference() {
        MfTransaction transaction = sipPurchase();
        SipMandate mandate = SipMandate.builder()
                .id(5L).folioId(FOLIO_ID).schemeId(SCHEME_ID)
                .amount(new BigDecimal("2000")).frequency(SipFrequency.MONTHLY)
                .startDate(BUSINESS_DATE).nextDueDate(BUSINESS_DATE).sipDay(9)
                .status(SipMandateStatus.ACTIVE).mandateReference("NACH-ABCD1234EFGH5678")
                .initiatedByUserId(10L).build();

        when(sipMandateRepository.findById(5L)).thenReturn(Optional.of(mandate));

        notificationService.onTransactionCreated(new TransactionCreatedEvent(this, transaction));

        ArgumentCaptor<NotificationPayload> captor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(emailChannel).send(captor.capture());
        assertThat(captor.getValue().subject()).isEqualTo("Your SIP installment has been initiated");

        var model = (NotificationPayload.TransactionCreatedModel) captor.getValue().templateModel();
        assertThat(model.sipMandateReference()).isEqualTo("NACH-ABCD1234EFGH5678");
    }

    @Test
    @DisplayName("onTransactionCreated: missing folio skips dispatch instead of throwing")
    void missingFolioSkipsDispatch() {
        when(folioRepository.findById(FOLIO_ID)).thenReturn(Optional.empty());
        MfTransaction transaction = manualPurchase();

        notificationService.onTransactionCreated(new TransactionCreatedEvent(this, transaction));

        verify(emailChannel, never()).send(any());
        verifyNoInteractions(notificationRepository);
    }

    // ─── onTransactionSettled ────────────────────────────────────────────────

    @Test
    @DisplayName("onTransactionSettled: ALLOTTED manual purchase uses the completed subject")
    void allottedManualPurchaseUsesCompletedSubject() {
        MfTransaction transaction = manualPurchase();
        transaction = MfTransaction.builder()
                .id(transaction.getId()).folioId(FOLIO_ID).schemeId(SCHEME_ID)
                .type(TransactionType.PURCHASE).status(TransactionStatus.ALLOTTED)
                .requestAmount(new BigDecimal("5000.00")).allottedUnits(new BigDecimal("103.6765"))
                .idempotencyKey("idem-001").initiatedByUserId(10L)
                .initiatedByRole(InitiatedByRole.INVESTOR).requestedAt(Instant.now())
                .businessDate(BUSINESS_DATE).build();

        notificationService.onTransactionSettled(new TransactionSettledEvent(this, transaction, null));

        ArgumentCaptor<NotificationPayload> captor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(emailChannel).send(captor.capture());
        assertThat(captor.getValue().subject()).isEqualTo("Your purchase has been completed");
    }

    @Test
    @DisplayName("onTransactionSettled: FAILED redemption uses the failed subject and a translated reason")
    void failedRedemptionUsesFailedSubjectAndTranslatedReason() {
        MfTransaction transaction = MfTransaction.builder()
                .id(TXN_ID).folioId(FOLIO_ID).schemeId(SCHEME_ID)
                .type(TransactionType.REDEMPTION).status(TransactionStatus.FAILED)
                .requestUnits(new BigDecimal("500.0000")).idempotencyKey("idem-002")
                .initiatedByUserId(10L).initiatedByRole(InitiatedByRole.INVESTOR)
                .requestedAt(Instant.now()).businessDate(BUSINESS_DATE).build();

        notificationService.onTransactionSettled(
                new TransactionSettledEvent(this, transaction, "Holding update failed after 3 retries"));

        ArgumentCaptor<NotificationPayload> captor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(emailChannel).send(captor.capture());
        assertThat(captor.getValue().subject()).isEqualTo("Your redemption could not be completed");

        var model = (NotificationPayload.TransactionSettledModel) captor.getValue().templateModel();
        assertThat(model.failureReason()).doesNotContain("Holding update failed after 3 retries");
    }

    // ─── onSipMandateCreated ─────────────────────────────────────────────────

    @Test
    @DisplayName("onSipMandateCreated: dispatches with the fixed subject and the passed-in scheduleDescription")
    void sipMandateCreatedDispatchesWithScheduleDescription() {
        SipMandate mandate = SipMandate.builder()
                .id(5L).folioId(FOLIO_ID).schemeId(SCHEME_ID)
                .amount(new BigDecimal("2000")).frequency(SipFrequency.MONTHLY)
                .startDate(BUSINESS_DATE).nextDueDate(BUSINESS_DATE).sipDay(9)
                .status(SipMandateStatus.ACTIVE).mandateReference("NACH-ABCD1234EFGH5678")
                .initiatedByUserId(10L).build();

        notificationService.onSipMandateCreated(
                new SipMandateCreatedEvent(this, mandate, "Deducted on the 9th of each month"));

        ArgumentCaptor<NotificationPayload> captor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(emailChannel).send(captor.capture());
        assertThat(captor.getValue().subject()).isEqualTo("Your SIP mandate has been registered");

        var model = (NotificationPayload.SipMandateCreatedModel) captor.getValue().templateModel();
        assertThat(model.scheduleDescription()).isEqualTo("Deducted on the 9th of each month");
        assertThat(model.mandateReference()).isEqualTo("NACH-ABCD1234EFGH5678");
    }

    // ─── fixtures ─────────────────────────────────────────────────────────────

    private MfTransaction manualPurchase() {
        return MfTransaction.builder()
                .id(TXN_ID).folioId(FOLIO_ID).schemeId(SCHEME_ID)
                .type(TransactionType.PURCHASE).status(TransactionStatus.PENDING)
                .requestAmount(new BigDecimal("5000.00")).idempotencyKey("idem-001")
                .initiatedByUserId(10L).initiatedByRole(InitiatedByRole.INVESTOR)
                .requestedAt(Instant.now()).businessDate(BUSINESS_DATE).build();
    }

    private MfTransaction sipPurchase() {
        return MfTransaction.builder()
                .id(TXN_ID).folioId(FOLIO_ID).schemeId(SCHEME_ID)
                .type(TransactionType.PURCHASE).status(TransactionStatus.PENDING)
                .requestAmount(new BigDecimal("2000.00")).idempotencyKey("idem-sip-001")
                .initiatedByUserId(10L).initiatedByRole(InitiatedByRole.INVESTOR)
                .requestedAt(Instant.now()).businessDate(BUSINESS_DATE)
                .sipMandateId(5L).build();
    }
}
