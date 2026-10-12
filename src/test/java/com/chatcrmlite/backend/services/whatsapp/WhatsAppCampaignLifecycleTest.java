package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.clients.MetaWhatsAppClient;
import com.chatcrmlite.backend.dtos.ScheduleCampaignRequestDto;
import com.chatcrmlite.backend.dtos.UpdateCampaignRequestDto;
import com.chatcrmlite.backend.models.*;
import com.chatcrmlite.backend.models.TenantSubscription.BillingCycle;
import com.chatcrmlite.backend.models.TenantSubscription.SubscriptionStatus;
import com.chatcrmlite.backend.repositories.*;
import com.chatcrmlite.backend.security.JwtUtils;
import com.chatcrmlite.backend.services.DistributedSchedulerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = com.chatcrmlite.backend.ChatCrmBackendApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
public class WhatsAppCampaignLifecycleTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private SubscriptionPlanRepository subscriptionPlanRepository;
    @Autowired private TenantSubscriptionRepository tenantSubscriptionRepository;
    @Autowired private WhatsAppCampaignRepository campaignRepository;
    @Autowired private WhatsAppCampaignAuditLogRepository auditLogRepository;
    @Autowired private WhatsAppTemplateRepository templateRepository;
    @Autowired private WhatsAppTemplateSnapshotRepository templateSnapshotRepository;
    @Autowired private UserSessionRepository sessionRepository;
    @Autowired private JwtUtils jwtUtils;
    @Autowired private PasswordEncoder passwordEncoder;

    @MockBean private MetaWhatsAppClient metaWhatsAppClient;
    @MockBean private DistributedSchedulerService distributedSchedulerService;

    private Tenant tenantA;
    private Tenant tenantB;
    private User tenantAAdmin;
    private User tenantBAdmin;
    private String tokenAAdmin;
    private String tokenBAdmin;
    private WhatsAppCampaign campaignA;

    @BeforeEach
    void setUp() {
        sessionRepository.deleteAll();
        auditLogRepository.deleteAll();
        campaignRepository.deleteAll();
        templateSnapshotRepository.deleteAll();
        templateRepository.deleteAll();
        userRepository.deleteAll();
        tenantSubscriptionRepository.deleteAll();
        tenantRepository.deleteAll();

        SubscriptionPlan proPlan = subscriptionPlanRepository.findById("PRO").orElseGet(() -> {
            SubscriptionPlan plan = new SubscriptionPlan("PRO", "Pro Pack", BigDecimal.valueOf(2999), BigDecimal.valueOf(28790), 10, 1000000, 1000000, 1000000, 25000, true, true, true);
            return subscriptionPlanRepository.save(plan);
        });

        tenantA = Tenant.builder().businessName("Tenant A Corp").businessType("retail").build();
        tenantB = Tenant.builder().businessName("Tenant B Corp").businessType("tech").build();
        tenantA = tenantRepository.save(tenantA);
        tenantB = tenantRepository.save(tenantB);

        TenantSubscription subA = new TenantSubscription();
        subA.setTenant(tenantA);
        subA.setPlan(proPlan);
        subA.setBillingCycle(BillingCycle.MONTHLY);
        subA.setStatus(SubscriptionStatus.ACTIVE);
        subA.setCurrentPeriodStart(LocalDateTime.now());
        subA.setCurrentPeriodEnd(LocalDateTime.now().plusMonths(1));
        tenantSubscriptionRepository.save(subA);

        TenantSubscription subB = new TenantSubscription();
        subB.setTenant(tenantB);
        subB.setPlan(proPlan);
        subB.setBillingCycle(BillingCycle.MONTHLY);
        subB.setStatus(SubscriptionStatus.ACTIVE);
        subB.setCurrentPeriodStart(LocalDateTime.now());
        subB.setCurrentPeriodEnd(LocalDateTime.now().plusMonths(1));
        tenantSubscriptionRepository.save(subB);

        tenantAAdmin = User.builder()
                .email("admin@tenant-a.com")
                .password(passwordEncoder.encode("Password123!"))
                .role(User.Role.ADMIN)
                .accountStatus(User.AccountStatus.ACTIVE)
                .tenant(tenantA)
                .build();
        tenantAAdmin = userRepository.save(tenantAAdmin);

        tenantBAdmin = User.builder()
                .email("admin@tenant-b.com")
                .password(passwordEncoder.encode("Password123!"))
                .role(User.Role.ADMIN)
                .accountStatus(User.AccountStatus.ACTIVE)
                .tenant(tenantB)
                .build();
        tenantBAdmin = userRepository.save(tenantBAdmin);

        String sessA = "sess-a-" + UUID.randomUUID();
        sessionRepository.save(UserSession.builder().tokenId(sessA).user(tenantAAdmin).status("ACTIVE").createdAt(LocalDateTime.now()).expiresAt(LocalDateTime.now().plusDays(1)).build());
        tokenAAdmin = jwtUtils.generateJwtToken(tenantAAdmin.getEmail(), sessA);

        String sessB = "sess-b-" + UUID.randomUUID();
        sessionRepository.save(UserSession.builder().tokenId(sessB).user(tenantBAdmin).status("ACTIVE").createdAt(LocalDateTime.now()).expiresAt(LocalDateTime.now().plusDays(1)).build());
        tokenBAdmin = jwtUtils.generateJwtToken(tenantBAdmin.getEmail(), sessB);

        WhatsAppTemplate template = WhatsAppTemplate.builder()
                .name("diwali_promo")
                .language("en_US")
                .category("MARKETING")
                .status("APPROVED")
                .bodyText("Hello {{1}}, happy diwali!")
                .owner(tenantAAdmin)
                .build();
        template.setTenant(tenantA);
        template = templateRepository.save(template);

        WhatsAppTemplateSnapshot snapshot = WhatsAppTemplateSnapshot.builder()
                .originalTemplateId(template.getId())
                .name(template.getName())
                .language(template.getLanguage())
                .category(template.getCategory())
                .status(template.getStatus())
                .bodyText(template.getBodyText())
                .build();
        snapshot.setTenant(tenantA);
        snapshot = templateSnapshotRepository.save(snapshot);

        campaignA = WhatsAppCampaign.builder()
                .name("Diwali Sale Broadcast")
                .owner(tenantAAdmin)
                .status(WhatsAppCampaign.Status.DRAFT)
                .templateSnapshot(snapshot)
                .targetType(WhatsAppCampaign.TargetType.ALL_CONTACTS)
                .variableMappingJson("{\"{{1}}\": \"contact.name\"}")
                .build();
        campaignA.setTenant(tenantA);
        campaignA = campaignRepository.save(campaignA);
    }

    @Test
    @DisplayName("1. Scheduling campaign registers one-time job in Quartz distributed scheduler")
    void testScheduleCampaignRegistersQuartzJob() throws Exception {
        LocalDateTime futureTime = LocalDateTime.now().plusHours(3);
        ScheduleCampaignRequestDto req = new ScheduleCampaignRequestDto(futureTime);

        mockMvc.perform(post("/api/v1/whatsapp/campaigns/" + campaignA.getId() + "/schedule")
                        .header("Authorization", "Bearer " + tokenAAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("SCHEDULED")))
                .andExpect(jsonPath("$.name", is("Diwali Sale Broadcast")));

        verify(distributedSchedulerService, times(1)).scheduleOneTimeJob(
                eq("campaign-job-" + campaignA.getId()),
                any(),
                any(),
                any()
        );
    }

    @Test
    @DisplayName("2. Cancelling a SCHEDULED campaign immediately deschedules and deletes Quartz trigger")
    void testCancelScheduledCampaignDeschedulesQuartz() throws Exception {
        // Pre-set campaign status to SCHEDULED
        campaignA.setStatus(WhatsAppCampaign.Status.SCHEDULED);
        campaignA.setScheduledAt(LocalDateTime.now().plusHours(2));
        campaignA = campaignRepository.save(campaignA);

        mockMvc.perform(post("/api/v1/whatsapp/campaigns/" + campaignA.getId() + "/cancel")
                        .header("Authorization", "Bearer " + tokenAAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("CANCELLED")));

        verify(distributedSchedulerService, times(1)).cancelJob("campaign-job-" + campaignA.getId());
    }

    @Test
    @DisplayName("3. Updating a SCHEDULED campaign atomically cancels old Quartz job and reschedules with new timestamp")
    void testUpdateScheduledCampaignReschedulesQuartz() throws Exception {
        campaignA.setStatus(WhatsAppCampaign.Status.SCHEDULED);
        campaignA.setScheduledAt(LocalDateTime.now().plusHours(2));
        campaignA = campaignRepository.save(campaignA);

        WhatsAppTemplate templateV2 = WhatsAppTemplate.builder()
                .name("diwali_promo_v2")
                .language("en_US")
                .category("MARKETING")
                .status("APPROVED")
                .bodyText("Hello {{1}}, mega diwali offer!")
                .owner(tenantAAdmin)
                .build();
        templateV2.setTenant(tenantA);
        templateRepository.save(templateV2);

        LocalDateTime newTime = LocalDateTime.now().plusHours(5);
        UpdateCampaignRequestDto updateDto = UpdateCampaignRequestDto.builder()
                .name("Updated Diwali Megasale")
                .templateId("diwali_promo_v2")
                .scheduledAt(newTime)
                .build();

        mockMvc.perform(put("/api/v1/whatsapp/campaigns/" + campaignA.getId())
                        .header("Authorization", "Bearer " + tokenAAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Updated Diwali Megasale")))
                .andExpect(jsonPath("$.templateSnapshot.name", is("diwali_promo_v2")))
                .andExpect(jsonPath("$.status", is("SCHEDULED")));

        verify(distributedSchedulerService, times(1)).cancelJob("campaign-job-" + campaignA.getId());
        verify(distributedSchedulerService, times(1)).scheduleOneTimeJob(
                eq("campaign-job-" + campaignA.getId()),
                any(),
                any(),
                any()
        );
    }

    @Test
    @DisplayName("4. Updating a DRAFT campaign updates details without touching scheduler")
    void testUpdateDraftCampaign() throws Exception {
        UpdateCampaignRequestDto updateDto = UpdateCampaignRequestDto.builder()
                .name("Renamed Draft Campaign")
                .templateId("new_template")
                .build();

        mockMvc.perform(put("/api/v1/whatsapp/campaigns/" + campaignA.getId())
                        .header("Authorization", "Bearer " + tokenAAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Renamed Draft Campaign")))
                .andExpect(jsonPath("$.status", is("DRAFT")));

        verify(distributedSchedulerService, never()).cancelJob(any());
        verify(distributedSchedulerService, never()).scheduleOneTimeJob(any(), any(), any(), any());
    }

    @Test
    @DisplayName("5. Updating a RUNNING or COMPLETED campaign is rejected with 400 Bad Request")
    void testUpdateRunningCampaignRejected() throws Exception {
        campaignA.setStatus(WhatsAppCampaign.Status.RUNNING);
        campaignA = campaignRepository.save(campaignA);

        UpdateCampaignRequestDto updateDto = UpdateCampaignRequestDto.builder()
                .name("Illegal Modification")
                .build();

        mockMvc.perform(put("/api/v1/whatsapp/campaigns/" + campaignA.getId())
                        .header("Authorization", "Bearer " + tokenAAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateDto)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("6. Updating schedule time to past or less than 1 minute in future is rejected with 400 Bad Request")
    void testUpdateScheduleNearFutureRejected() throws Exception {
        campaignA.setStatus(WhatsAppCampaign.Status.SCHEDULED);
        campaignA = campaignRepository.save(campaignA);

        UpdateCampaignRequestDto updateDto = UpdateCampaignRequestDto.builder()
                .scheduledAt(LocalDateTime.now().plusSeconds(20))
                .build();

        mockMvc.perform(put("/api/v1/whatsapp/campaigns/" + campaignA.getId())
                        .header("Authorization", "Bearer " + tokenAAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateDto)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("7. Cross-tenant modification attempt returns 404 Not Found (IDOR protection)")
    void testCrossTenantModificationBlocked() throws Exception {
        UpdateCampaignRequestDto updateDto = UpdateCampaignRequestDto.builder()
                .name("Malicious Hijack")
                .build();

        mockMvc.perform(put("/api/v1/whatsapp/campaigns/" + campaignA.getId())
                        .header("Authorization", "Bearer " + tokenBAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateDto)))
                .andExpect(status().isNotFound());
    }
}
