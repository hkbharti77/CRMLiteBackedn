package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.models.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class AIQuotaServiceTest {

    @Mock
    private TokenBudgetService tokenBudgetService;

    @InjectMocks
    private AIQuotaService aiQuotaService;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
    }

    @Test
    void testCheckAndEnforceQuota_UnderLimit_Success() {
        when(tokenBudgetService.getDailyUsage(tenantId)).thenReturn(5_000L); // 50% of FREE limit

        assertDoesNotThrow(() -> aiQuotaService.checkAndEnforceQuota(tenantId, User.PlanType.FREE));
        verify(tokenBudgetService).getDailyUsage(tenantId);
    }

    @Test
    void testCheckAndEnforceQuota_OverLimit_ThrowsException() {
        when(tokenBudgetService.getDailyUsage(tenantId)).thenReturn(10_500L); // Over FREE limit

        assertThrows(AIQuotaService.QuotaExceededException.class, 
            () -> aiQuotaService.checkAndEnforceQuota(tenantId, User.PlanType.FREE));
    }

    @Test
    void testCheckAndEnforceQuota_ProPlan_AllowsHigherLimit() {
        when(tokenBudgetService.getDailyUsage(tenantId)).thenReturn(50_000L); // Under PRO limit but over FREE limit

        assertDoesNotThrow(() -> aiQuotaService.checkAndEnforceQuota(tenantId, User.PlanType.PRO));
    }
}
