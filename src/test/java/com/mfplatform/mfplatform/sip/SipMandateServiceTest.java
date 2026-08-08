package com.mfplatform.mfplatform.sip;

import com.mfplatform.mfplatform.common.BusinessDateService;
import com.mfplatform.mfplatform.common.OwnershipValidator;
import com.mfplatform.mfplatform.common.Role;
import com.mfplatform.mfplatform.folio.FolioRepository;
import com.mfplatform.mfplatform.security.ActorContext;
import com.mfplatform.mfplatform.security.CurrentUserResolver;
import com.mfplatform.mfplatform.sip.dto.SipDtos.*;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SipMandateService.
 *
 * WHAT WE'RE TESTING:
 *
 * 1. Registration:
 *    - Happy path — valid mandate is created with correct fields
 *    - Start date in the past → rejected
 *    - End date before start date → rejected
 *    - Duplicate active SIP for same folio/scheme → rejected
 *    - Folio not owned by caller → AccessDeniedException
 *
 * 2. Lifecycle operations (pause/resume/cancel):
 *    - Valid state transitions succeed
 *    - Invalid transitions (pause a PAUSED mandate) → IllegalStateException
 *    - Ownership verified before mutation
 *
 * 3. nextDueDate = startDate on registration
 *    This is important: the SIP batch job's reader queries nextDueDate <= today
 *    to find due mandates. If nextDueDate starts as null, the first installment
 *    would never be processed.
 *
 * 4. Mandate reference is generated (NACH-style prefix)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SipMandateService")
class SipMandateServiceTest {

    @Mock private SipMandateRepository sipMandateRepository;
    @Mock private FolioRepository       folioRepository;
    @Mock private CurrentUserResolver   currentUserResolver;
    @Mock private OwnershipValidator    ownershipValidator;
    @Mock private BusinessDateService   businessDateService;
    @Mock private Authentication        authentication;

    @InjectMocks
    private SipMandateService sipMandateService;

    private static final Long INVESTOR_ID = 7L;
    private static final Long FOLIO_ID    = 42L;
    private static final Long SCHEME_ID   = 1L;
    private static final Long USER_ID     = 10L;

    private ActorContext investorActor;

    @BeforeEach
    void setUp() {
        investorActor = new ActorContext(USER_ID, Role.INVESTOR, INVESTOR_ID, null);
        // lenient: DomainMethodTests exercises SipMandate directly, never through
        // the service, so this stub is never consulted there.
        lenient().when(currentUserResolver.resolve(authentication)).thenReturn(investorActor);
    }

    // ─── register ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("register")
    class RegisterTests {

        private RegisterSipRequest validRequest;

        @BeforeEach
        void setUp() {
            validRequest = new RegisterSipRequest(
                    FOLIO_ID, SCHEME_ID,
                    new BigDecimal("2000.00"),
                    SipFrequency.MONTHLY,
                    LocalDate.now().plusDays(1), // starts tomorrow
                    null, // open-ended
                    15    // sipDay
            );

            // lenient: rejectsWhenFolioNotOwned() throws before this is ever consulted
            lenient().when(businessDateService.today()).thenReturn(LocalDate.now());
            // Ownership is checked before any other validation in register(), so
            // this stub is genuinely consulted by every test in this class — stays strict.
            when(ownershipValidator.isInvestorFolio(FOLIO_ID, INVESTOR_ID)).thenReturn(true);
            // lenient: the date-validation and folio-ownership negative-path tests
            // throw before register() ever reaches the duplicate-SIP check.
            lenient().when(sipMandateRepository.existsActiveMandateForFolioAndScheme(FOLIO_ID, SCHEME_ID))
                    .thenReturn(false);
            // lenient: every negative-path test in this class throws before register()
            // reaches the save() call.
            lenient().when(sipMandateRepository.save(any())).thenAnswer(i -> {
                SipMandate m = i.getArgument(0);
                // Simulate DB assigning an ID
                return SipMandate.builder()
                        .id(100L).folioId(m.getFolioId()).schemeId(m.getSchemeId())
                        .amount(m.getAmount()).frequency(m.getFrequency())
                        .startDate(m.getStartDate()).endDate(m.getEndDate())
                        .nextDueDate(m.getNextDueDate()).status(m.getStatus())
                        .mandateReference(m.getMandateReference())
                        .initiatedByUserId(m.getInitiatedByUserId())
                        .sipDay(m.getSipDay())
                        .build();
            });
        }

        @Test
        @DisplayName("creates mandate with correct fields on happy path")
        void createsValidMandate() {
            SipMandateResponse response =
                    sipMandateService.register(validRequest, authentication);

            assertThat(response).isNotNull();
            assertThat(response.folioId()).isEqualTo(FOLIO_ID);
            assertThat(response.schemeId()).isEqualTo(SCHEME_ID);
            assertThat(response.amount()).isEqualByComparingTo(new BigDecimal("2000.00"));
            assertThat(response.frequency()).isEqualTo("MONTHLY");
            assertThat(response.status()).isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("MONTHLY: first due date is startDate's own month when startDate day <= sipDay")
        void firstDueDateSameMonthWhenStartDayBeforeSipDay() {
            when(businessDateService.today()).thenReturn(LocalDate.of(2020, 1, 1));
            RegisterSipRequest request = new RegisterSipRequest(
                    FOLIO_ID, SCHEME_ID, new BigDecimal("2000.00"), SipFrequency.MONTHLY,
                    LocalDate.of(2026, 3, 10), null, 15);

            sipMandateService.register(request, authentication);

            ArgumentCaptor<SipMandate> captor = ArgumentCaptor.forClass(SipMandate.class);
            verify(sipMandateRepository).save(captor.capture());
            assertThat(captor.getValue().getNextDueDate()).isEqualTo(LocalDate.of(2026, 3, 15));
        }

        @Test
        @DisplayName("MONTHLY: first due date rolls to next month when startDate day > sipDay")
        void firstDueDateNextMonthWhenStartDayAfterSipDay() {
            when(businessDateService.today()).thenReturn(LocalDate.of(2020, 1, 1));
            RegisterSipRequest request = new RegisterSipRequest(
                    FOLIO_ID, SCHEME_ID, new BigDecimal("2000.00"), SipFrequency.MONTHLY,
                    LocalDate.of(2026, 3, 20), null, 15);

            sipMandateService.register(request, authentication);

            ArgumentCaptor<SipMandate> captor = ArgumentCaptor.forClass(SipMandate.class);
            verify(sipMandateRepository).save(captor.capture());
            assertThat(captor.getValue().getNextDueDate()).isEqualTo(LocalDate.of(2026, 4, 15));
        }

        @Test
        @DisplayName("MONTHLY: first due date equals startDate when startDate day == sipDay")
        void firstDueDateSameDayWhenStartDayEqualsSipDay() {
            when(businessDateService.today()).thenReturn(LocalDate.of(2020, 1, 1));
            RegisterSipRequest request = new RegisterSipRequest(
                    FOLIO_ID, SCHEME_ID, new BigDecimal("2000.00"), SipFrequency.MONTHLY,
                    LocalDate.of(2026, 3, 15), null, 15);

            sipMandateService.register(request, authentication);

            ArgumentCaptor<SipMandate> captor = ArgumentCaptor.forClass(SipMandate.class);
            verify(sipMandateRepository).save(captor.capture());
            assertThat(captor.getValue().getNextDueDate()).isEqualTo(LocalDate.of(2026, 3, 15));
        }

        @Test
        @DisplayName("MONTHLY: sipDay=31 clamps to the last real day of a short month")
        void firstDueDateClampsToShortMonth() {
            when(businessDateService.today()).thenReturn(LocalDate.of(2020, 1, 1));
            RegisterSipRequest request = new RegisterSipRequest(
                    FOLIO_ID, SCHEME_ID, new BigDecimal("2000.00"), SipFrequency.MONTHLY,
                    LocalDate.of(2026, 2, 1), null, 31);

            sipMandateService.register(request, authentication);

            ArgumentCaptor<SipMandate> captor = ArgumentCaptor.forClass(SipMandate.class);
            verify(sipMandateRepository).save(captor.capture());
            // 2026 is not a leap year — February has 28 days
            assertThat(captor.getValue().getNextDueDate()).isEqualTo(LocalDate.of(2026, 2, 28));
        }

        @Test
        @DisplayName("rejects MONTHLY registration without sipDay")
        void rejectsMonthlyWithoutSipDay() {
            RegisterSipRequest request = new RegisterSipRequest(
                    FOLIO_ID, SCHEME_ID, new BigDecimal("2000.00"), SipFrequency.MONTHLY,
                    LocalDate.now().plusDays(1), null, null);

            assertThatThrownBy(() ->
                sipMandateService.register(request, authentication)
            )
            .isInstanceOf(TransactionValidationException.class)
            .hasMessageContaining("sipDay is required");

            verify(sipMandateRepository, never()).save(any());
        }

        @Test
        @DisplayName("rejects MONTHLY registration with sipDay out of 1-31 range")
        void rejectsMonthlySipDayOutOfRange() {
            RegisterSipRequest request = new RegisterSipRequest(
                    FOLIO_ID, SCHEME_ID, new BigDecimal("2000.00"), SipFrequency.MONTHLY,
                    LocalDate.now().plusDays(1), null, 32);

            assertThatThrownBy(() ->
                sipMandateService.register(request, authentication)
            )
            .isInstanceOf(TransactionValidationException.class)
            .hasMessageContaining("between 1 and 31");

            verify(sipMandateRepository, never()).save(any());
        }

        @Test
        @DisplayName("WEEKLY registration ignores a client-supplied sipDay — stored mandate has sipDay=null")
        void ignoresSipDayForWeekly() {
            when(businessDateService.today()).thenReturn(LocalDate.of(2020, 1, 1));
            RegisterSipRequest request = new RegisterSipRequest(
                    FOLIO_ID, SCHEME_ID, new BigDecimal("2000.00"), SipFrequency.WEEKLY,
                    LocalDate.of(2026, 3, 10), null, 15); // sipDay sent but should be ignored

            sipMandateService.register(request, authentication);

            ArgumentCaptor<SipMandate> captor = ArgumentCaptor.forClass(SipMandate.class);
            verify(sipMandateRepository).save(captor.capture());
            assertThat(captor.getValue().getSipDay()).isNull();
        }

        @Test
        @DisplayName("QUARTERLY registration ignores a client-supplied sipDay — stored mandate has sipDay=null")
        void ignoresSipDayForQuarterly() {
            when(businessDateService.today()).thenReturn(LocalDate.of(2020, 1, 1));
            RegisterSipRequest request = new RegisterSipRequest(
                    FOLIO_ID, SCHEME_ID, new BigDecimal("2000.00"), SipFrequency.QUARTERLY,
                    LocalDate.of(2026, 3, 10), null, 15); // sipDay sent but should be ignored

            sipMandateService.register(request, authentication);

            ArgumentCaptor<SipMandate> captor = ArgumentCaptor.forClass(SipMandate.class);
            verify(sipMandateRepository).save(captor.capture());
            assertThat(captor.getValue().getSipDay()).isNull();
        }

        @Test
        @DisplayName("scheduleDescription for MONTHLY interpolates sipDay with correct ordinal suffix")
        void scheduleDescriptionForMonthly() {
            when(businessDateService.today()).thenReturn(LocalDate.of(2020, 1, 1));
            RegisterSipRequest request = new RegisterSipRequest(
                    FOLIO_ID, SCHEME_ID, new BigDecimal("2000.00"), SipFrequency.MONTHLY,
                    LocalDate.of(2026, 3, 1), null, 21); // 21st -> "21st", not "21th"

            SipMandateResponse response = sipMandateService.register(request, authentication);

            assertThat(response.scheduleDescription()).isEqualTo("Deducted on the 21st of each month");
        }

        @Test
        @DisplayName("scheduleDescription for MONTHLY handles the 11th/12th/13th 'th' exception")
        void scheduleDescriptionForMonthlyTeensException() {
            when(businessDateService.today()).thenReturn(LocalDate.of(2020, 1, 1));
            RegisterSipRequest request = new RegisterSipRequest(
                    FOLIO_ID, SCHEME_ID, new BigDecimal("2000.00"), SipFrequency.MONTHLY,
                    LocalDate.of(2026, 3, 1), null, 11);

            SipMandateResponse response = sipMandateService.register(request, authentication);

            assertThat(response.scheduleDescription()).isEqualTo("Deducted on the 11th of each month");
        }

        @Test
        @DisplayName("scheduleDescription for WEEKLY is the fixed anchor text")
        void scheduleDescriptionForWeekly() {
            when(businessDateService.today()).thenReturn(LocalDate.of(2020, 1, 1));
            RegisterSipRequest request = new RegisterSipRequest(
                    FOLIO_ID, SCHEME_ID, new BigDecimal("2000.00"), SipFrequency.WEEKLY,
                    LocalDate.of(2026, 3, 1), null, null);

            SipMandateResponse response = sipMandateService.register(request, authentication);

            assertThat(response.scheduleDescription())
                    .isEqualTo("Deducted on the 7th, 14th, 21st, and 28th of each month");
        }

        @Test
        @DisplayName("scheduleDescription for QUARTERLY is the fixed anchor text")
        void scheduleDescriptionForQuarterly() {
            when(businessDateService.today()).thenReturn(LocalDate.of(2020, 1, 1));
            RegisterSipRequest request = new RegisterSipRequest(
                    FOLIO_ID, SCHEME_ID, new BigDecimal("2000.00"), SipFrequency.QUARTERLY,
                    LocalDate.of(2026, 3, 1), null, null);

            SipMandateResponse response = sipMandateService.register(request, authentication);

            assertThat(response.scheduleDescription())
                    .isEqualTo("Deducted on the 8th of January, April, July, and October");
        }

        @Test
        @DisplayName("mandate reference is generated with NACH prefix")
        void mandateReferenceHasNachPrefix() {
            sipMandateService.register(validRequest, authentication);

            ArgumentCaptor<SipMandate> captor = ArgumentCaptor.forClass(SipMandate.class);
            verify(sipMandateRepository).save(captor.capture());

            assertThat(captor.getValue().getMandateReference())
                    .startsWith("NACH-")
                    .hasSize(21); // NACH- (5) + 16 hex chars
        }

        @Test
        @DisplayName("initiatedByUserId is set from actor context")
        void initiatedByUserIdIsSet() {
            sipMandateService.register(validRequest, authentication);

            ArgumentCaptor<SipMandate> captor = ArgumentCaptor.forClass(SipMandate.class);
            verify(sipMandateRepository).save(captor.capture());

            assertThat(captor.getValue().getInitiatedByUserId()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("rejects registration when start date is in the past")
        void rejectsStartDateInPast() {
            RegisterSipRequest pastStartRequest = new RegisterSipRequest(
                    FOLIO_ID, SCHEME_ID, new BigDecimal("2000.00"),
                    SipFrequency.MONTHLY,
                    LocalDate.now().minusDays(1), // yesterday
                    null,
                    15
            );

            assertThatThrownBy(() ->
                sipMandateService.register(pastStartRequest, authentication)
            )
            .isInstanceOf(TransactionValidationException.class)
            .hasMessageContaining("past");
        }

        @Test
        @DisplayName("rejects registration when end date is before start date")
        void rejectsEndDateBeforeStartDate() {
            LocalDate startDate = LocalDate.now().plusDays(10);
            RegisterSipRequest badDates = new RegisterSipRequest(
                    FOLIO_ID, SCHEME_ID, new BigDecimal("2000.00"),
                    SipFrequency.MONTHLY,
                    startDate,
                    startDate.minusDays(1), // end before start
                    15
            );

            assertThatThrownBy(() ->
                sipMandateService.register(badDates, authentication)
            )
            .isInstanceOf(TransactionValidationException.class)
            .hasMessageContaining("end date");
        }

        @Test
        @DisplayName("rejects duplicate active SIP for same folio and scheme")
        void rejectsDuplicateActiveSip() {
            when(sipMandateRepository.existsActiveMandateForFolioAndScheme(FOLIO_ID, SCHEME_ID))
                    .thenReturn(true); // already exists

            assertThatThrownBy(() ->
                sipMandateService.register(validRequest, authentication)
            )
            .isInstanceOf(TransactionValidationException.class)
            .hasMessageContaining("active SIP already exists");
        }

        @Test
        @DisplayName("throws AccessDeniedException when investor does not own the folio")
        void rejectsWhenFolioNotOwned() {
            when(ownershipValidator.isInvestorFolio(FOLIO_ID, INVESTOR_ID))
                    .thenReturn(false); // not their folio

            assertThatThrownBy(() ->
                sipMandateService.register(validRequest, authentication)
            )
            .isInstanceOf(AccessDeniedException.class);

            // No mandate should be saved
            verify(sipMandateRepository, never()).save(any());
        }
    }

    // ─── pause ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("pause")
    class PauseTests {

        private SipMandate activeMandateEntity;

        @BeforeEach
        void setUp() {
            activeMandateEntity = SipMandate.builder()
                    .id(100L).folioId(FOLIO_ID).schemeId(SCHEME_ID)
                    .amount(new BigDecimal("2000.00"))
                    .frequency(SipFrequency.MONTHLY)
                    .startDate(LocalDate.now().minusMonths(1))
                    .nextDueDate(LocalDate.now().plusDays(15))
                    .status(SipMandateStatus.ACTIVE)
                    .mandateReference("NACH-ABCD12345678EFGH")
                    .initiatedByUserId(USER_ID)
                    .sipDay(15)
                    .build();

            when(sipMandateRepository.findById(100L))
                    .thenReturn(Optional.of(activeMandateEntity));
            when(ownershipValidator.isInvestorFolio(FOLIO_ID, INVESTOR_ID))
                    .thenReturn(true);
            // lenient: throwsWhenAlreadyPaused/throwsWhenNotOwner throw before
            // pause() reaches save().
            lenient().when(sipMandateRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        }

        @Test
        @DisplayName("pauses an ACTIVE mandate successfully")
        void pausesActiveMandateSuccessfully() {
            SipMandateResponse response =
                    sipMandateService.pause(100L, authentication);

            assertThat(response.status()).isEqualTo("PAUSED");
        }

        @Test
        @DisplayName("throws when trying to pause an already PAUSED mandate")
        void throwsWhenAlreadyPaused() {
            // First pause it
            activeMandateEntity.pause();
            // Now try to pause again
            assertThatThrownBy(() ->
                sipMandateService.pause(100L, authentication)
            ).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("throws AccessDeniedException when investor does not own the folio")
        void throwsWhenNotOwner() {
            when(ownershipValidator.isInvestorFolio(FOLIO_ID, INVESTOR_ID))
                    .thenReturn(false);

            assertThatThrownBy(() ->
                sipMandateService.pause(100L, authentication)
            ).isInstanceOf(AccessDeniedException.class);

            verify(sipMandateRepository, never()).save(any());
        }
    }

    // ─── resume ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("resume")
    class ResumeTests {

        private SipMandate pausedMandate;

        @BeforeEach
        void setUp() {
            pausedMandate = SipMandate.builder()
                    .id(100L).folioId(FOLIO_ID).schemeId(SCHEME_ID)
                    .amount(new BigDecimal("2000.00"))
                    .frequency(SipFrequency.MONTHLY)
                    .startDate(LocalDate.now().minusMonths(1))
                    .nextDueDate(LocalDate.now().plusDays(15))
                    .status(SipMandateStatus.PAUSED)
                    .mandateReference("NACH-ABCD12345678EFGH")
                    .initiatedByUserId(USER_ID)
                    .sipDay(15)
                    .build();

            when(sipMandateRepository.findById(100L))
                    .thenReturn(Optional.of(pausedMandate));
            when(ownershipValidator.isInvestorFolio(FOLIO_ID, INVESTOR_ID))
                    .thenReturn(true);
            // lenient: throwsWhenResumingActiveMandate throws before resume() reaches save().
            lenient().when(sipMandateRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        }

        @Test
        @DisplayName("resumes a PAUSED mandate successfully")
        void resumesPausedMandate() {
            SipMandateResponse response =
                    sipMandateService.resume(100L, authentication);

            assertThat(response.status()).isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("throws when trying to resume an ACTIVE mandate")
        void throwsWhenResumingActiveMandate() {
            // Make it active
            pausedMandate.resume(); // now ACTIVE
            // Try to resume again
            assertThatThrownBy(() ->
                sipMandateService.resume(100L, authentication)
            ).isInstanceOf(IllegalStateException.class);
        }
    }

    // ─── cancel ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("cancel")
    class CancelTests {

        private SipMandate activeMandateEntity;

        @BeforeEach
        void setUp() {
            activeMandateEntity = SipMandate.builder()
                    .id(100L).folioId(FOLIO_ID).schemeId(SCHEME_ID)
                    .amount(new BigDecimal("2000.00"))
                    .frequency(SipFrequency.MONTHLY)
                    .startDate(LocalDate.now().minusMonths(1))
                    .nextDueDate(LocalDate.now().plusDays(15))
                    .status(SipMandateStatus.ACTIVE)
                    .mandateReference("NACH-ABCD12345678EFGH")
                    .initiatedByUserId(USER_ID)
                    .sipDay(15)
                    .build();

            // lenient: throwsWhenMandateNotFound calls cancel(999L, ...) — findById(100L)
            // never matches, so cancel() never reaches the ownership check or save()
            // either. throwsWhenAlreadyCancelled reaches findById/ownership but throws
            // before save().
            lenient().when(sipMandateRepository.findById(100L))
                    .thenReturn(Optional.of(activeMandateEntity));
            lenient().when(ownershipValidator.isInvestorFolio(FOLIO_ID, INVESTOR_ID))
                    .thenReturn(true);
            lenient().when(sipMandateRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        }

        @Test
        @DisplayName("cancels an ACTIVE mandate — status becomes CANCELLED")
        void cancelsActiveMandateSuccessfully() {
            SipMandateResponse response =
                    sipMandateService.cancel(100L, authentication);

            assertThat(response.status()).isEqualTo("CANCELLED");
        }

        @Test
        @DisplayName("cancels a PAUSED mandate — status becomes CANCELLED")
        void cancelsPausedMandate() {
            activeMandateEntity.pause();
            when(sipMandateRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            SipMandateResponse response =
                    sipMandateService.cancel(100L, authentication);

            assertThat(response.status()).isEqualTo("CANCELLED");
        }

        @Test
        @DisplayName("throws when trying to cancel an already CANCELLED mandate")
        void throwsWhenAlreadyCancelled() {
            activeMandateEntity.cancel(); // already cancelled

            assertThatThrownBy(() ->
                sipMandateService.cancel(100L, authentication)
            ).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("throws when mandate not found")
        void throwsWhenMandateNotFound() {
            when(sipMandateRepository.findById(999L))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                sipMandateService.cancel(999L, authentication)
            ).isInstanceOf(SipMandateNotFoundException.class);
        }
    }

    // ─── SipMandate domain methods ────────────────────────────────────────────

    @Nested
    @DisplayName("SipMandate domain methods (via service)")
    class DomainMethodTests {

        private SipMandate mandateWith(SipFrequency frequency, LocalDate nextDueDate, Integer sipDay) {
            return SipMandate.builder()
                    .id(1L).folioId(FOLIO_ID).schemeId(SCHEME_ID)
                    .amount(new BigDecimal("2000")).frequency(frequency)
                    .startDate(nextDueDate).nextDueDate(nextDueDate).sipDay(sipDay)
                    .status(SipMandateStatus.ACTIVE)
                    .mandateReference("NACH-TEST").initiatedByUserId(USER_ID)
                    .build();
        }

        // ── advanceNextDueDate() — anchor-based, not incremental arithmetic ──

        @Test
        @DisplayName("MONTHLY advances to the next occurrence of sipDay")
        void monthlyAdvancesToNextSipDay() {
            SipMandate mandate = mandateWith(SipFrequency.MONTHLY, LocalDate.of(2026, 3, 15), 15);

            mandate.advanceNextDueDate();

            assertThat(mandate.getNextDueDate()).isEqualTo(LocalDate.of(2026, 4, 15));
        }

        @Test
        @DisplayName("MONTHLY sipDay=31 clamps to February's last real day, not an error")
        void monthlyClampsToShortMonth() {
            SipMandate mandate = mandateWith(SipFrequency.MONTHLY, LocalDate.of(2026, 1, 31), 31);

            mandate.advanceNextDueDate();

            // 2026 is not a leap year — February has 28 days
            assertThat(mandate.getNextDueDate()).isEqualTo(LocalDate.of(2026, 2, 28));
        }

        @Test
        @DisplayName("WEEKLY advances to the next anchor within the same month")
        void weeklyAdvancesToNextAnchorWithinMonth() {
            SipMandate mandate = mandateWith(SipFrequency.WEEKLY, LocalDate.of(2026, 3, 7), null);

            mandate.advanceNextDueDate();

            assertThat(mandate.getNextDueDate()).isEqualTo(LocalDate.of(2026, 3, 14));
        }

        @Test
        @DisplayName("WEEKLY wraps from the 28th to the 7th of next month, not 28+7")
        void weeklyWrapsToNextMonthAfter28th() {
            SipMandate mandate = mandateWith(SipFrequency.WEEKLY, LocalDate.of(2026, 3, 28), null);

            mandate.advanceNextDueDate();

            assertThat(mandate.getNextDueDate()).isEqualTo(LocalDate.of(2026, 4, 7));
        }

        @Test
        @DisplayName("QUARTERLY advances to the next quarterly anchor within the same year")
        void quarterlyAdvancesToNextAnchorWithinYear() {
            SipMandate mandate = mandateWith(SipFrequency.QUARTERLY, LocalDate.of(2026, 1, 8), null);

            mandate.advanceNextDueDate();

            assertThat(mandate.getNextDueDate()).isEqualTo(LocalDate.of(2026, 4, 8));
        }

        @Test
        @DisplayName("QUARTERLY wraps from October 8th to January 8th of next year")
        void quarterlyWrapsToNextYearAfterOctober() {
            SipMandate mandate = mandateWith(SipFrequency.QUARTERLY, LocalDate.of(2026, 10, 8), null);

            mandate.advanceNextDueDate();

            assertThat(mandate.getNextDueDate()).isEqualTo(LocalDate.of(2027, 1, 8));
        }

        @Test
        @DisplayName("mandate auto-completes when the advanced nextDueDate passes endDate")
        void autoCompletesWhenEndDatePassed() {
            SipMandate mandate = SipMandate.builder()
                    .id(1L).folioId(FOLIO_ID).schemeId(SCHEME_ID)
                    .amount(new BigDecimal("2000")).frequency(SipFrequency.MONTHLY)
                    .startDate(LocalDate.of(2026, 3, 15))
                    .endDate(LocalDate.of(2026, 4, 14)) // one day before the next due date
                    .nextDueDate(LocalDate.of(2026, 3, 15))
                    .sipDay(15)
                    .status(SipMandateStatus.ACTIVE)
                    .mandateReference("NACH-TEST").initiatedByUserId(USER_ID)
                    .build();

            // After advancing, nextDueDate = 2026-04-15, which is after endDate
            mandate.advanceNextDueDate();

            assertThat(mandate.getStatus()).isEqualTo(SipMandateStatus.COMPLETED);
        }

        @Test
        @DisplayName("mandate does NOT auto-complete when endDate is null (open-ended)")
        void doesNotCompleteWhenOpenEnded() {
            SipMandate mandate = mandateWith(SipFrequency.MONTHLY, LocalDate.of(2026, 3, 15), 15);

            mandate.advanceNextDueDate();

            // Open-ended SIP stays ACTIVE indefinitely
            assertThat(mandate.getStatus()).isEqualTo(SipMandateStatus.ACTIVE);
        }

        // ── computeFirstDueDate() — same anchor logic, seeded from startDate ──

        @Test
        @DisplayName("computeFirstDueDate MONTHLY: startDate day <= sipDay stays in the same month")
        void computeFirstDueDateMonthlySameMonth() {
            LocalDate result = SipMandate.computeFirstDueDate(LocalDate.of(2026, 3, 10), SipFrequency.MONTHLY, 15);
            assertThat(result).isEqualTo(LocalDate.of(2026, 3, 15));
        }

        @Test
        @DisplayName("computeFirstDueDate MONTHLY: startDate day > sipDay rolls to next month")
        void computeFirstDueDateMonthlyNextMonth() {
            LocalDate result = SipMandate.computeFirstDueDate(LocalDate.of(2026, 3, 20), SipFrequency.MONTHLY, 15);
            assertThat(result).isEqualTo(LocalDate.of(2026, 4, 15));
        }

        @Test
        @DisplayName("computeFirstDueDate WEEKLY: nearest anchor on or after startDate")
        void computeFirstDueDateWeekly() {
            LocalDate result = SipMandate.computeFirstDueDate(LocalDate.of(2026, 3, 10), SipFrequency.WEEKLY, null);
            assertThat(result).isEqualTo(LocalDate.of(2026, 3, 14));
        }

        @Test
        @DisplayName("computeFirstDueDate QUARTERLY: nearest anchor on or after startDate")
        void computeFirstDueDateQuarterly() {
            LocalDate result = SipMandate.computeFirstDueDate(LocalDate.of(2026, 3, 1), SipFrequency.QUARTERLY, null);
            assertThat(result).isEqualTo(LocalDate.of(2026, 4, 8));
        }
    }
}
