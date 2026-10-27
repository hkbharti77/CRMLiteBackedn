package com.chatcrmlite.backend.services.billing;

import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.TenantCreditReservation;
import com.chatcrmlite.backend.models.TenantCreditReservation.ReservationStatus;
import com.chatcrmlite.backend.models.TenantCreditTransaction;
import com.chatcrmlite.backend.repositories.TenantCreditReservationRepository;
import com.chatcrmlite.backend.repositories.TenantCreditTransactionRepository;
import com.chatcrmlite.backend.repositories.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CreditMeteringServiceTest {

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private TenantCreditTransactionRepository creditTransactionRepository;

    @Mock
    private TenantCreditReservationRepository creditReservationRepository;

    @InjectMocks
    private CreditMeteringService creditMeteringService;

    private UUID tenantId;
    private Tenant tenant;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        tenant = new Tenant();
        tenant.setId(tenantId);
        tenant.setBusinessName("Test Tenant");
        tenant.setAiCreditBalance(100L);
    }

    @Test
    @DisplayName("hasAvailableCredits returns true when balance exceeds reserved + required")
    void testHasAvailableCredits_Success() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(creditReservationRepository.sumActiveReservedCredits(eq(tenantId), any(LocalDateTime.class))).thenReturn(20L);

        // balance is 100, activeReserved is 20 -> available is 80. Required is 50.
        assertTrue(creditMeteringService.hasAvailableCredits(tenantId, 50L));
    }

    @Test
    @DisplayName("hasAvailableCredits returns false when available credits are below required")
    void testHasAvailableCredits_Insufficient() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(creditReservationRepository.sumActiveReservedCredits(eq(tenantId), any(LocalDateTime.class))).thenReturn(80L);

        // balance is 100, activeReserved is 80 -> available is 20. Required is 50.
        assertFalse(creditMeteringService.hasAvailableCredits(tenantId, 50L));
    }

    @Test
    @DisplayName("reserveCredits succeeds and creates PENDING reservation with TTL")
    void testReserveCredits_Success() {
        when(tenantRepository.findByIdForUpdate(tenantId)).thenReturn(Optional.of(tenant));
        when(creditReservationRepository.sumActiveReservedCredits(eq(tenantId), any(LocalDateTime.class))).thenReturn(10L);

        UUID generatedReservationId = UUID.randomUUID();
        when(creditReservationRepository.save(any(TenantCreditReservation.class))).thenAnswer(invocation -> {
            TenantCreditReservation r = invocation.getArgument(0);
            r.setId(generatedReservationId);
            return r;
        });

        UUID resId = creditMeteringService.reserveCredits(tenantId, 30L, Duration.ofMinutes(5), "ref-123");

        assertNotNull(resId);
        assertEquals(generatedReservationId, resId);

        ArgumentCaptor<TenantCreditReservation> captor = ArgumentCaptor.forClass(TenantCreditReservation.class);
        verify(creditReservationRepository).save(captor.capture());
        TenantCreditReservation saved = captor.getValue();
        assertEquals(30L, saved.getAmount());
        assertEquals(ReservationStatus.PENDING, saved.getStatus());
        assertEquals("ref-123", saved.getReferenceId());
    }

    @Test
    @DisplayName("reserveCredits throws InsufficientCreditsException when available < requested")
    void testReserveCredits_ThrowsWhenInsufficient() {
        when(tenantRepository.findByIdForUpdate(tenantId)).thenReturn(Optional.of(tenant));
        when(creditReservationRepository.sumActiveReservedCredits(eq(tenantId), any(LocalDateTime.class))).thenReturn(90L);

        // 100 - 90 = 10 available, but requested is 20
        assertThrows(CreditMeteringService.InsufficientCreditsException.class, () ->
                creditMeteringService.reserveCredits(tenantId, 20L, Duration.ofMinutes(5), "ref-overflow"));
    }

    @Test
    @DisplayName("settleReservation updates reservation to SETTLED, deducts tenant balance, and records transaction")
    void testSettleReservation_Success() {
        UUID resId = UUID.randomUUID();
        TenantCreditReservation reservation = TenantCreditReservation.builder()
                .id(resId)
                .tenant(tenant)
                .amount(15L)
                .status(ReservationStatus.PENDING)
                .referenceId("ref-msg-1")
                .build();

        when(creditReservationRepository.findById(resId)).thenReturn(Optional.of(reservation));
        when(tenantRepository.findByIdForUpdate(tenantId)).thenReturn(Optional.of(tenant));

        creditMeteringService.settleReservation(resId, 15L, "Streaming complete");

        assertEquals(ReservationStatus.SETTLED, reservation.getStatus());
        assertNotNull(reservation.getSettledAt());
        verify(creditReservationRepository).save(reservation);

        // Balance 100 - 15 = 85
        assertEquals(85L, tenant.getAiCreditBalance());
        verify(tenantRepository).save(tenant);

        ArgumentCaptor<TenantCreditTransaction> txCaptor = ArgumentCaptor.forClass(TenantCreditTransaction.class);
        verify(creditTransactionRepository).save(txCaptor.capture());
        TenantCreditTransaction tx = txCaptor.getValue();
        assertEquals(-15L, tx.getAmount());
        assertEquals(85L, tx.getBalanceAfter());
        assertEquals("USAGE_DEDUCTION", tx.getCategory());
    }

    @Test
    @DisplayName("releaseReservation updates status to RELEASED without deducting tenant balance")
    void testReleaseReservation_Success() {
        UUID resId = UUID.randomUUID();
        TenantCreditReservation reservation = TenantCreditReservation.builder()
                .id(resId)
                .tenant(tenant)
                .amount(25L)
                .status(ReservationStatus.PENDING)
                .build();

        when(creditReservationRepository.findById(resId)).thenReturn(Optional.of(reservation));

        creditMeteringService.releaseReservation(resId);

        assertEquals(ReservationStatus.RELEASED, reservation.getStatus());
        assertNotNull(reservation.getSettledAt());
        verify(creditReservationRepository).save(reservation);
        verify(tenantRepository, never()).save(any());
        verify(creditTransactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("reconcileStaleReservations marks expired pending reservations as EXPIRED")
    void testReconcileStaleReservations() {
        TenantCreditReservation stale1 = TenantCreditReservation.builder()
                .id(UUID.randomUUID())
                .tenant(tenant)
                .status(ReservationStatus.PENDING)
                .build();
        TenantCreditReservation stale2 = TenantCreditReservation.builder()
                .id(UUID.randomUUID())
                .tenant(tenant)
                .status(ReservationStatus.PENDING)
                .build();

        when(creditReservationRepository.findStaleReservations(any(LocalDateTime.class)))
                .thenReturn(List.of(stale1, stale2));

        int reconciled = creditMeteringService.reconcileStaleReservations();

        assertEquals(2, reconciled);
        assertEquals(ReservationStatus.EXPIRED, stale1.getStatus());
        assertEquals(ReservationStatus.EXPIRED, stale2.getStatus());
        verify(creditReservationRepository, times(2)).save(any(TenantCreditReservation.class));
    }
}
