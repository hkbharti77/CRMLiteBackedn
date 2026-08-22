package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.models.SubscriptionPlan;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.TenantSubscription;
import com.chatcrmlite.backend.models.TenantSubscription.SubscriptionStatus;
import com.chatcrmlite.backend.repositories.*;
import com.chatcrmlite.backend.services.tenant.QuotaEnforcerService;
import com.chatcrmlite.backend.services.tenant.QuotaEnforcerService.QuotaExceededException;
import com.chatcrmlite.backend.services.tenant.QuotaEnforcerService.SubscriptionExpiredException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QuotaEnforcerServiceTest {

    @Mock private TenantSubscriptionRepository tenantSubscriptionRepository;
    @Mock private SubscriptionPlanRepository subscriptionPlanRepository;
    @Mock private UserRepository userRepository;
    @Mock private LeadRepository leadRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private AppointmentRepository appointmentRepository;
    @Mock private TicketRepository ticketRepository;
    @Mock private CustomEmailRepository customEmailRepository;
    @Mock private TenantRepository tenantRepository;
    @Mock private org.springframework.context.ApplicationEventPublisher eventPublisher;
    @Mock private com.chatcrmlite.backend.services.tenant.EntitlementResolverService entitlementResolverService;

    @InjectMocks
    private QuotaEnforcerService quotaEnforcerService;

    private UUID tenantId;
    private SubscriptionPlan freePlan;
    private SubscriptionPlan proPlan;
    private TenantSubscription activeFreeSub;
    private com.chatcrmlite.backend.dtos.EffectiveEntitlementsDTO freeEntitlements;
    private com.chatcrmlite.backend.dtos.EffectiveEntitlementsDTO proEntitlements;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        tenantId = UUID.randomUUID();

        freePlan = new SubscriptionPlan("FREE", "Free Pack", BigDecimal.ZERO, BigDecimal.ZERO, 1, 100, 15, 10, 500, false, false, false);
        proPlan = new SubscriptionPlan("PRO", "Pro Pack", BigDecimal.valueOf(2999), BigDecimal.valueOf(28790), 10, 1000000, 1000000, 1000000, 25000, true, true, true);

        Tenant tenant = new Tenant();
        tenant.setId(tenantId);
        tenant.setPrimaryResource(Tenant.PrimaryResource.LEAD);

        activeFreeSub = TenantSubscription.builder()
                .plan(freePlan)
                .status(SubscriptionStatus.FREE_TRIAL)
                .currentPeriodStart(LocalDateTime.now())
                .currentPeriodEnd(LocalDateTime.now().plusMonths(1))
                .tenant(tenant)
                .build();

        freeEntitlements = com.chatcrmlite.backend.dtos.EffectiveEntitlementsDTO.builder()
                .basePlanId("FREE")
                .limits(com.chatcrmlite.backend.dtos.EffectiveEntitlementsDTO.LimitsDTO.builder()
                        .employeeLimit(1)
                        .primaryResourceLimit(100)
                        .secondaryResourceLimit(15)
                        .ticketLimit(10)
                        .emailLimit(500)
                        .build())
                .features(com.chatcrmlite.backend.dtos.EffectiveEntitlementsDTO.FeaturesDTO.builder()
                        .hasWhatsapp(false)
                        .hasWhatsappCampaign(false)
                        .hasCustomWidget(false)
                        .build())
                .build();

        proEntitlements = com.chatcrmlite.backend.dtos.EffectiveEntitlementsDTO.builder()
                .basePlanId("PRO")
                .limits(com.chatcrmlite.backend.dtos.EffectiveEntitlementsDTO.LimitsDTO.builder()
                        .employeeLimit(10)
                        .primaryResourceLimit(1000000)
                        .secondaryResourceLimit(1000000)
                        .ticketLimit(1000000)
                        .emailLimit(25000)
                        .build())
                .features(com.chatcrmlite.backend.dtos.EffectiveEntitlementsDTO.FeaturesDTO.builder()
                        .hasWhatsapp(true)
                        .hasWhatsappCampaign(true)
                        .hasCustomWidget(true)
                        .build())
                .build();

        lenient().when(entitlementResolverService.getEffectiveEntitlements(tenantId)).thenReturn(freeEntitlements);
    }

    @Test
    void testGetActiveSubscription_Existing() {
        when(tenantSubscriptionRepository.findByTenantId(tenantId)).thenReturn(Optional.of(activeFreeSub));
        TenantSubscription resolved = quotaEnforcerService.getActiveSubscription(tenantId);
        assertNotNull(resolved);
        assertEquals("FREE", resolved.getPlan().getId());
    }

    @Test
    void testGetActiveSubscription_DefaultCreatedIfNull() {
        Tenant tenant = new Tenant();
        tenant.setId(tenantId);
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(tenantSubscriptionRepository.findByTenantId(tenantId)).thenReturn(Optional.empty());
        when(subscriptionPlanRepository.findById("FREE")).thenReturn(Optional.of(freePlan));
        when(tenantSubscriptionRepository.save(any(TenantSubscription.class))).thenAnswer(i -> i.getArguments()[0]);

        TenantSubscription resolved = quotaEnforcerService.getActiveSubscription(tenantId);
        assertNotNull(resolved);
        assertEquals("FREE", resolved.getPlan().getId());
    }

    @Test
    void testVerifyEmployeeSeatQuota_WithinLimit() {
        when(tenantSubscriptionRepository.findByTenantId(tenantId)).thenReturn(Optional.of(activeFreeSub));
        when(userRepository.countByTenantId(tenantId)).thenReturn(0L); // 0 employees registered

        assertDoesNotThrow(() -> quotaEnforcerService.verifyEmployeeSeatQuota(tenantId));
    }

    @Test
    void testVerifyEmployeeSeatQuota_ExceededLimit() {
        when(tenantSubscriptionRepository.findByTenantId(tenantId)).thenReturn(Optional.of(activeFreeSub));
        when(userRepository.countByTenantId(tenantId)).thenReturn(1L); // 1 employee registered (Limit is 1)

        assertThrows(QuotaExceededException.class, () -> quotaEnforcerService.verifyEmployeeSeatQuota(tenantId));
    }

    @Test
    void testVerifyLeadQuota_WithinLimit() {
        when(tenantSubscriptionRepository.findByTenantId(tenantId)).thenReturn(Optional.of(activeFreeSub));
        when(leadRepository.countByTenantId(tenantId)).thenReturn(99L);

        assertDoesNotThrow(() -> quotaEnforcerService.verifyLeadQuota(tenantId));
    }

    @Test
    void testVerifyLeadQuota_ExceededLimit() {
        when(tenantSubscriptionRepository.findByTenantId(tenantId)).thenReturn(Optional.of(activeFreeSub));
        when(leadRepository.countByTenantId(tenantId)).thenReturn(100L); // Limit is 100

        assertThrows(QuotaExceededException.class, () -> quotaEnforcerService.verifyLeadQuota(tenantId));
    }

    @Test
    void testVerifyWhatsAppIntegrationAllowed_FreeTierForbidden() {
        when(tenantSubscriptionRepository.findByTenantId(tenantId)).thenReturn(Optional.of(activeFreeSub));
        assertThrows(QuotaExceededException.class, () -> quotaEnforcerService.verifyWhatsAppIntegrationAllowed(tenantId));
    }

    @Test
    void testVerifyWhatsAppIntegrationAllowed_ProTierAllowed() {
        Tenant tenant = new Tenant();
        tenant.setId(tenantId);
        TenantSubscription proSub = TenantSubscription.builder()
                .plan(proPlan)
                .status(SubscriptionStatus.ACTIVE)
                .tenant(tenant)
                .build();

        when(tenantSubscriptionRepository.findByTenantId(tenantId)).thenReturn(Optional.of(proSub));
        when(entitlementResolverService.getEffectiveEntitlements(tenantId)).thenReturn(proEntitlements);
        assertDoesNotThrow(() -> quotaEnforcerService.verifyWhatsAppIntegrationAllowed(tenantId));
    }

    @Test
    void testVerifyExpiredSubscriptionDowngradesToFree() {
        Tenant tenant = new Tenant();
        tenant.setId(tenantId);
        TenantSubscription expiredSub = TenantSubscription.builder()
                .plan(proPlan)
                .status(SubscriptionStatus.CANCELLED)
                .currentPeriodStart(LocalDateTime.now().minusMonths(2))
                .currentPeriodEnd(LocalDateTime.now().minusDays(1)) // Expired yesterday
                .tenant(tenant)
                .build();

        when(tenantSubscriptionRepository.findByTenantId(tenantId)).thenReturn(Optional.of(expiredSub));
        when(subscriptionPlanRepository.findById("FREE")).thenReturn(Optional.of(freePlan));
        when(tenantSubscriptionRepository.save(any(TenantSubscription.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TenantSubscription result = quotaEnforcerService.getActiveSubscription(tenantId);
        assertEquals(freePlan, result.getPlan());
        assertEquals(SubscriptionStatus.FREE_TRIAL, result.getStatus());
    }
}
