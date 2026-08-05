package com.mfplatform.mfplatform.investor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("KycVerificationWorker")
class KycVerificationWorkerTest {

    @Mock private InvestorRepository investorRepository;

    @InjectMocks
    private KycVerificationWorker kycVerificationWorker;

    private Investor pending1;
    private Investor pending2;

    @BeforeEach
    void setUp() {
        pending1 = Investor.builder()
                .id(1L).name("Priya Sharma").email("priya@example.com")
                .panNumber("ABCPS1234F").kycComplete(false).build();

        pending2 = Investor.builder()
                .id(2L).name("Rahul Mehta").email("rahul@example.com")
                .panNumber("BCDRM5678G").kycComplete(false).build();
    }

    @Test
    @DisplayName("marks every kycComplete=false investor as verified and saves it")
    void marksAllPendingInvestorsVerified() {
        when(investorRepository.findByKycComplete(false)).thenReturn(List.of(pending1, pending2));

        kycVerificationWorker.processKycVerifications();

        assertThat(pending1.isKycComplete()).isTrue();
        assertThat(pending2.isKycComplete()).isTrue();

        ArgumentCaptor<Investor> captor = ArgumentCaptor.forClass(Investor.class);
        verify(investorRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).containsExactlyInAnyOrder(pending1, pending2);
    }

    @Test
    @DisplayName("does nothing when there are no investors awaiting KYC")
    void doesNothingWhenNoneArePending() {
        when(investorRepository.findByKycComplete(false)).thenReturn(List.of());

        kycVerificationWorker.processKycVerifications();

        verify(investorRepository, never()).save(any());
    }
}
