package com.mfplatform.mfplatform.portfolio;

import com.mfplatform.mfplatform.common.BusinessDateService;
import com.mfplatform.mfplatform.folio.Folio;
import com.mfplatform.mfplatform.folio.FolioRepository;
import com.mfplatform.mfplatform.investor.InvestorRepository;
import com.mfplatform.mfplatform.nav.NavHistory;
import com.mfplatform.mfplatform.nav.NavHistoryRepository;
import com.mfplatform.mfplatform.portfolio.dto.PortfolioDtos.FolioPortfolio;
import com.mfplatform.mfplatform.portfolio.dto.PortfolioDtos.HoldingView;
import com.mfplatform.mfplatform.scheme.Scheme;
import com.mfplatform.mfplatform.scheme.SchemeCategory;
import com.mfplatform.mfplatform.scheme.SchemeRepository;
import com.mfplatform.mfplatform.sip.SipMandateRepository;
import com.mfplatform.mfplatform.transaction.Holding;
import com.mfplatform.mfplatform.transaction.HoldingRepository;
import com.mfplatform.mfplatform.transaction.MfTransactionRepository;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for PortfolioService — specifically the current-value computation
 * bug fix: NAV lookups must use BusinessDateService.today(), not the real
 * system clock, and a missing NAV must surface as null (unavailable), never
 * as a silently-defaulted ZERO that reads as a 100% loss.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PortfolioService")
class PortfolioServiceTest {

    @Mock private HoldingRepository holdingRepository;
    @Mock private MfTransactionRepository transactionRepository;
    @Mock private NavHistoryRepository navHistoryRepository;
    @Mock private SchemeRepository schemeRepository;
    @Mock private FolioRepository folioRepository;
    @Mock private InvestorRepository investorRepository;
    @Mock private SipMandateRepository sipMandateRepository;
    @Mock private BusinessDateService businessDateService;

    @InjectMocks
    private PortfolioService portfolioService;

    private static final Long FOLIO_ID = 1L;
    private static final Long SCHEME_ID = 10L;

    private Folio folio;
    private Holding holding;
    private Scheme scheme;

    @BeforeEach
    void setUp() {
        folio = Folio.builder().id(FOLIO_ID).folioNumber("FL0001").investorId(7L).build();
        holding = Holding.builder().id(100L).folioId(FOLIO_ID).schemeId(SCHEME_ID)
                .unitsHeld(new BigDecimal("50.0000")).build();
        scheme = Scheme.builder().id(SCHEME_ID).schemeName("Blue Chip Fund")
                .schemeCode("BCF001").category(SchemeCategory.EQUITY).build();

        when(holdingRepository.findByFolioId(FOLIO_ID)).thenReturn(List.of(holding));
        when(schemeRepository.findById(SCHEME_ID)).thenReturn(Optional.of(scheme));
        when(transactionRepository.findActiveForFolioAndScheme(FOLIO_ID, SCHEME_ID))
                .thenReturn(List.of());
    }

    @Nested
    @DisplayName("current value NAV lookup")
    class CurrentValueLookup {

        @Test
        @DisplayName("uses businessDateService.today(), not the real system clock")
        void usesBusinessDateNotRealClock() {
            LocalDate realToday = LocalDate.now();
            LocalDate virtualBusinessDate = realToday.plusMonths(3); // deliberately different
            when(businessDateService.today()).thenReturn(virtualBusinessDate);

            NavHistory nav = NavHistory.builder()
                    .navDate(virtualBusinessDate).navValue(new BigDecimal("50.0000")).build();
            when(navHistoryRepository.findLatestNavOnOrBefore(SCHEME_ID, virtualBusinessDate))
                    .thenReturn(Optional.of(nav));

            portfolioService.buildFolioPortfolio(folio);

            ArgumentCaptor<LocalDate> dateCaptor = ArgumentCaptor.forClass(LocalDate.class);
            verify(navHistoryRepository).findLatestNavOnOrBefore(eq(SCHEME_ID), dateCaptor.capture());
            assertThat(dateCaptor.getValue()).isEqualTo(virtualBusinessDate);
            assertThat(dateCaptor.getValue()).isNotEqualTo(realToday);
        }

        @Test
        @DisplayName("computes current value from units held x latest NAV when a NAV exists")
        void computesCurrentValueWhenNavExists() {
            LocalDate businessDate = LocalDate.of(2027, 3, 15);
            when(businessDateService.today()).thenReturn(businessDate);

            NavHistory nav = NavHistory.builder()
                    .navDate(businessDate).navValue(new BigDecimal("50.0000")).build();
            when(navHistoryRepository.findLatestNavOnOrBefore(SCHEME_ID, businessDate))
                    .thenReturn(Optional.of(nav));

            FolioPortfolio result = portfolioService.buildFolioPortfolio(folio);

            HoldingView view = result.holdings().get(0);
            assertThat(view.currentValue()).isEqualByComparingTo(new BigDecimal("2500.00"));
            assertThat(view.latestNavValue()).isEqualByComparingTo(new BigDecimal("50.0000"));
            assertThat(view.latestNavDate()).isEqualTo(businessDate);
        }
    }

    @Nested
    @DisplayName("no NAV available")
    class NoNavAvailable {

        @Test
        @DisplayName("currentValue and absoluteReturnPct are null, not ZERO")
        void doesNotDefaultToZero() {
            LocalDate businessDate = LocalDate.of(2027, 3, 15);
            when(businessDateService.today()).thenReturn(businessDate);
            when(navHistoryRepository.findLatestNavOnOrBefore(SCHEME_ID, businessDate))
                    .thenReturn(Optional.empty());

            FolioPortfolio result = portfolioService.buildFolioPortfolio(folio);

            HoldingView view = result.holdings().get(0);
            assertThat(view.currentValue()).isNull();
            assertThat(view.absoluteReturnPct()).isNull();
            assertThat(view.latestNavValue()).isNull();
            assertThat(view.latestNavDate()).isNull();
            // Units held must remain correct regardless of NAV availability.
            assertThat(view.unitsHeld()).isEqualByComparingTo(new BigDecimal("50.0000"));
        }

        @Test
        @DisplayName("folio total current value excludes holdings with no NAV rather than going null")
        void folioTotalSkipsUnavailableHoldings() {
            LocalDate businessDate = LocalDate.of(2027, 3, 15);
            when(businessDateService.today()).thenReturn(businessDate);
            when(navHistoryRepository.findLatestNavOnOrBefore(SCHEME_ID, businessDate))
                    .thenReturn(Optional.empty());

            FolioPortfolio result = portfolioService.buildFolioPortfolio(folio);

            assertThat(result.totalCurrentValue()).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }
}
