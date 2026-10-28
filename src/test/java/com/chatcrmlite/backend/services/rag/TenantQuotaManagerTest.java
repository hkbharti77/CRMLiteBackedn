package com.chatcrmlite.backend.services.rag;

import com.chatcrmlite.backend.exceptions.QuotaExceededException;
import com.chatcrmlite.backend.models.RagQuotaReservation;
import com.chatcrmlite.backend.models.TenantRagQuota;
import com.chatcrmlite.backend.repositories.RagQuotaReservationRepository;
import com.chatcrmlite.backend.repositories.TenantRagQuotaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TenantQuotaManagerTest {

    @Mock
    private TenantRagQuotaRepository tenantQuotaRepository;

    @Mock
    private RagQuotaReservationRepository quotaReservationRepository;

    @Mock
    private com.chatcrmlite.backend.services.tenant.EntitlementResolverService entitlementResolverService;

    @InjectMocks
    private TenantQuotaManager quotaManager;

    private UUID tenantId;
    private UUID jobId;
    private TenantRagQuota quota;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        jobId = UUID.randomUUID();
        quota = TenantRagQuota.builder()
                .tenantId(tenantId)
                .dailyPageLimit(50)
                .dailyPagesConsumed(10)
                .monthlyChunkLimit(2500)
                .monthlyChunksConsumed(200)
                .timezone("UTC")
                .lastDailyResetAt(Instant.now())
                .lastMonthlyResetAt(Instant.now())
                .build();
    }

    @Test
    @DisplayName("Should successfully reserve quota when within daily and monthly limits")
    void testReserveQuota_Success() {
        when(quotaReservationRepository.findByJobId(jobId)).thenReturn(Optional.empty());
        when(tenantQuotaRepository.findByTenantIdForUpdate(tenantId)).thenReturn(Optional.of(quota));
        when(quotaReservationRepository.sumPendingPagesByTenantId(eq(tenantId), any())).thenReturn(5);
        when(quotaReservationRepository.sumPendingChunksByTenantId(eq(tenantId), any())).thenReturn(50);
        when(quotaReservationRepository.save(any(RagQuotaReservation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        RagQuotaReservation result = quotaManager.reserveQuota(tenantId, jobId, 10, 100);

        assertNotNull(result);
        assertEquals(tenantId, result.getTenantId());
        assertEquals(jobId, result.getJobId());
        assertEquals(10, result.getPagesReserved());
        assertEquals(100, result.getChunksReserved());
        assertEquals("PENDING", result.getStatus());
        verify(quotaReservationRepository, times(1)).save(any(RagQuotaReservation.class));
    }

    @Test
    @DisplayName("Should throw QuotaExceededException when requested pages exceed daily limit")
    void testReserveQuota_DailyLimitExceeded() {
        when(quotaReservationRepository.findByJobId(jobId)).thenReturn(Optional.empty());
        when(tenantQuotaRepository.findByTenantIdForUpdate(tenantId)).thenReturn(Optional.of(quota));
        when(quotaReservationRepository.sumPendingPagesByTenantId(eq(tenantId), any())).thenReturn(35);
        // Consumed 10 + Pending 35 = 45. Limit is 50. Requesting 10 will require 55 > 50.

        QuotaExceededException ex = assertThrows(QuotaExceededException.class, () ->
                quotaManager.reserveQuota(tenantId, jobId, 10, 50));

        assertEquals("DAILY_PAGES", ex.getQuotaType());
        assertEquals(50, ex.getLimit());
        assertEquals(10, ex.getRequested());
        assertEquals(5, ex.getAvailable());
        verify(quotaReservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should throw QuotaExceededException when requested chunks exceed monthly limit")
    void testReserveQuota_MonthlyLimitExceeded() {
        when(quotaReservationRepository.findByJobId(jobId)).thenReturn(Optional.empty());
        when(tenantQuotaRepository.findByTenantIdForUpdate(tenantId)).thenReturn(Optional.of(quota));
        when(quotaReservationRepository.sumPendingPagesByTenantId(eq(tenantId), any())).thenReturn(0);
        when(quotaReservationRepository.sumPendingChunksByTenantId(eq(tenantId), any())).thenReturn(2200);
        // Consumed 200 + Pending 2200 = 2400. Limit is 2500. Requesting 200 will require 2600 > 2500.

        QuotaExceededException ex = assertThrows(QuotaExceededException.class, () ->
                quotaManager.reserveQuota(tenantId, jobId, 5, 200));

        assertEquals("MONTHLY_CHUNKS", ex.getQuotaType());
        assertEquals(2500, ex.getLimit());
        assertEquals(200, ex.getRequested());
        assertEquals(100, ex.getAvailable());
        verify(quotaReservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should return existing reservation idempotently if already pending")
    void testReserveQuota_IdempotentExisting() {
        RagQuotaReservation existing = RagQuotaReservation.builder()
                .tenantId(tenantId)
                .jobId(jobId)
                .pagesReserved(15)
                .chunksReserved(150)
                .status("PENDING")
                .build();
        when(quotaReservationRepository.findByJobId(jobId)).thenReturn(Optional.of(existing));

        RagQuotaReservation result = quotaManager.reserveQuota(tenantId, jobId, 15, 150);

        assertSame(existing, result);
        verify(tenantQuotaRepository, never()).findByTenantIdForUpdate(any());
    }

    @Test
    @DisplayName("Should atomically settle reservation and increment tenant counters")
    void testSettleReservation_Success() {
        RagQuotaReservation pending = RagQuotaReservation.builder()
                .tenantId(tenantId)
                .jobId(jobId)
                .pagesReserved(10)
                .chunksReserved(100)
                .status("PENDING")
                .build();

        when(tenantQuotaRepository.findByTenantIdForUpdate(tenantId)).thenReturn(Optional.of(quota));
        when(quotaReservationRepository.findPendingByJobIdForUpdate(jobId)).thenReturn(Optional.of(pending));
        when(quotaReservationRepository.commitPendingReservation(eq(jobId), eq(8), eq(80), any()))
                .thenReturn(1);

        quotaManager.settleReservation(tenantId, jobId, 8, 80);

        verify(tenantQuotaRepository).findByTenantIdForUpdate(tenantId);
        verify(quotaReservationRepository).commitPendingReservation(eq(jobId), eq(8), eq(80), any());
        verify(tenantQuotaRepository).incrementConsumedCounters(tenantId, 8, 80);
    }

    @Test
    @DisplayName("Should be idempotent during settlement if reservation is not PENDING")
    void testSettleReservation_IdempotentAlreadySettled() {
        when(tenantQuotaRepository.findByTenantIdForUpdate(tenantId)).thenReturn(Optional.of(quota));
        when(quotaReservationRepository.findPendingByJobIdForUpdate(jobId)).thenReturn(Optional.empty());

        quotaManager.settleReservation(tenantId, jobId, 8, 80);

        verify(tenantQuotaRepository).findByTenantIdForUpdate(tenantId);
        verify(quotaReservationRepository, never()).commitPendingReservation(any(), any(), any(), any());
        verify(tenantQuotaRepository, never()).incrementConsumedCounters(any(), any(), any());
    }

    @Test
    @DisplayName("Should release reservation on cancellation or error")
    void testReleaseReservation_Success() {
        when(tenantQuotaRepository.findByTenantIdForUpdate(tenantId)).thenReturn(Optional.of(quota));
        when(quotaReservationRepository.releasePendingReservation(eq(jobId), any())).thenReturn(1);

        quotaManager.releaseReservation(tenantId, jobId);

        verify(tenantQuotaRepository).findByTenantIdForUpdate(tenantId);
        verify(quotaReservationRepository).releasePendingReservation(eq(jobId), any());
    }

    @Test
    @DisplayName("Should renew reservation lease for long-running crawls")
    void testRenewReservation_Success() {
        when(quotaReservationRepository.renewPendingReservation(eq(jobId), any(), any())).thenReturn(1);

        boolean renewed = quotaManager.renewReservation(jobId, Duration.ofMinutes(10));

        assertTrue(renewed);
        verify(quotaReservationRepository).renewPendingReservation(eq(jobId), any(), any());
    }
}
