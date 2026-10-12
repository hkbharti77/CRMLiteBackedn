package com.chatcrmlite.backend.security;

import com.chatcrmlite.backend.controllers.SmsCampaignController.CreateCampaignRequest;
import com.chatcrmlite.backend.models.SubscriptionPlan;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.TenantSubscription;
import com.chatcrmlite.backend.models.TenantSubscription.BillingCycle;
import com.chatcrmlite.backend.models.TenantSubscription.SubscriptionStatus;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.UserSession;
import com.chatcrmlite.backend.models.sms.SmsCampaign;
import com.chatcrmlite.backend.models.sms.SmsTemplate;
import com.chatcrmlite.backend.repositories.*;
import com.chatcrmlite.backend.services.sms.SmsCampaignService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = com.chatcrmlite.backend.ChatCrmBackendApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
public class SmsCrossTenantSecurityTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private SubscriptionPlanRepository subscriptionPlanRepository;
    @Autowired private TenantSubscriptionRepository tenantSubscriptionRepository;
    @Autowired private SmsCampaignRepository smsCampaignRepository;
    @Autowired private SmsCampaignRecipientRepository recipientRepository;
    @Autowired private SmsTemplateRepository smsTemplateRepository;
    @Autowired private UserSessionRepository sessionRepository;
    @Autowired private JwtUtils jwtUtils;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private SmsCampaignService smsCampaignService;

    private Tenant tenantA;
    private Tenant tenantB;

    private User tenantAAdmin;
    private User tenantAAgentNoPerm;
    private User tenantBAdmin;
    private User superAdmin;

    private String tokenAAdmin;
    private String tokenAAgentNoPerm;
    private String tokenBAdmin;
    private String tokenSuperAdmin;

    private SmsCampaign campaignA;
    private SmsCampaign campaignB;

    @BeforeEach
    void setUp() {
        sessionRepository.deleteAll();
        recipientRepository.deleteAll();
        smsCampaignRepository.deleteAll();
        smsTemplateRepository.deleteAll();
        userRepository.deleteAll();
        tenantSubscriptionRepository.deleteAll();
        tenantRepository.deleteAll();

        // 1. Pro plan with campaigns entitlement
        SubscriptionPlan proPlan = subscriptionPlanRepository.findById("PRO").orElseGet(() -> {
            SubscriptionPlan plan = new SubscriptionPlan("PRO", "Pro Pack", BigDecimal.valueOf(2999), BigDecimal.valueOf(28790), 10, 1000000, 1000000, 1000000, 25000, true, true, true);
            return subscriptionPlanRepository.save(plan);
        });

        // 2. Tenants
        tenantA = Tenant.builder().businessName("Tenant A Corp").businessType("retail").build();
        tenantB = Tenant.builder().businessName("Tenant B Corp").businessType("tech").build();
        tenantA = tenantRepository.save(tenantA);
        tenantB = tenantRepository.save(tenantB);

        // Subscriptions
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

        // 3. Users
        tenantAAdmin = User.builder()
                .email("admin@tenant-a.com")
                .password(passwordEncoder.encode("Password123!"))
                .role(User.Role.ADMIN)
                .accountStatus(User.AccountStatus.ACTIVE)
                .tenant(tenantA)
                .build();
        tenantAAdmin = userRepository.save(tenantAAdmin);

        tenantAAgentNoPerm = User.builder()
                .email("agent-noperm@tenant-a.com")
                .password(passwordEncoder.encode("Password123!"))
                .role(User.Role.AGENT)
                .accountStatus(User.AccountStatus.ACTIVE)
                .tenant(tenantA)
                .build();
        tenantAAgentNoPerm.setPermissions(Collections.emptyList());
        tenantAAgentNoPerm = userRepository.save(tenantAAgentNoPerm);

        tenantBAdmin = User.builder()
                .email("admin@tenant-b.com")
                .password(passwordEncoder.encode("Password123!"))
                .role(User.Role.ADMIN)
                .accountStatus(User.AccountStatus.ACTIVE)
                .tenant(tenantB)
                .build();
        tenantBAdmin = userRepository.save(tenantBAdmin);

        superAdmin = User.builder()
                .email("platform-superadmin@crmlite.com")
                .password(passwordEncoder.encode("Password123!"))
                .role(User.Role.SUPER_ADMIN)
                .accountStatus(User.AccountStatus.ACTIVE)
                .tenant(tenantA) // System platform tenant
                .build();
        superAdmin = userRepository.save(superAdmin);

        // Tokens & Sessions
        String sessAAdmin = "sess-a-admin-" + UUID.randomUUID();
        sessionRepository.save(UserSession.builder().tokenId(sessAAdmin).user(tenantAAdmin).status("ACTIVE").createdAt(LocalDateTime.now()).expiresAt(LocalDateTime.now().plusDays(1)).build());
        tokenAAdmin = jwtUtils.generateJwtToken(tenantAAdmin.getEmail(), sessAAdmin);

        String sessAAgentNoPerm = "sess-a-agent-noperm-" + UUID.randomUUID();
        sessionRepository.save(UserSession.builder().tokenId(sessAAgentNoPerm).user(tenantAAgentNoPerm).status("ACTIVE").createdAt(LocalDateTime.now()).expiresAt(LocalDateTime.now().plusDays(1)).build());
        tokenAAgentNoPerm = jwtUtils.generateJwtToken(tenantAAgentNoPerm.getEmail(), sessAAgentNoPerm);

        String sessBAdmin = "sess-b-admin-" + UUID.randomUUID();
        sessionRepository.save(UserSession.builder().tokenId(sessBAdmin).user(tenantBAdmin).status("ACTIVE").createdAt(LocalDateTime.now()).expiresAt(LocalDateTime.now().plusDays(1)).build());
        tokenBAdmin = jwtUtils.generateJwtToken(tenantBAdmin.getEmail(), sessBAdmin);

        String sessSuperAdmin = "sess-superadmin-" + UUID.randomUUID();
        sessionRepository.save(UserSession.builder().tokenId(sessSuperAdmin).user(superAdmin).status("ACTIVE").createdAt(LocalDateTime.now()).expiresAt(LocalDateTime.now().plusDays(1)).build());
        tokenSuperAdmin = jwtUtils.generateJwtToken(superAdmin.getEmail(), sessSuperAdmin);

        // 4. Create campaigns for Tenant A and Tenant B
        campaignA = new SmsCampaign();
        campaignA.setName("Tenant A SMS Campaign");
        campaignA.setTenant(tenantA);
        campaignA.setTenantId(tenantA.getId());
        campaignA.setBusinessId(tenantA.getId().toString());
        campaignA.setStatus("DRAFT");
        campaignA = smsCampaignRepository.save(campaignA);

        campaignB = new SmsCampaign();
        campaignB.setName("Tenant B SMS Campaign");
        campaignB.setTenant(tenantB);
        campaignB.setTenantId(tenantB.getId());
        campaignB.setBusinessId(tenantB.getId().toString());
        campaignB.setStatus("DRAFT");
        campaignB = smsCampaignRepository.save(campaignB);
    }

    @Test
    @DisplayName("1. Authenticated tenant A can access its own campaigns")
    void testTenantAAccessOwnCampaigns() throws Exception {
        mockMvc.perform(get("/api/v1/sms/campaigns")
                        .header("Authorization", "Bearer " + tokenAAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name", is("Tenant A SMS Campaign")))
                .andExpect(jsonPath("$[0].id", is(campaignA.getId().toString())));
    }

    @Test
    @DisplayName("2. Tenant A attempting to access Tenant B campaign by ID receives 404")
    void testTenantACannotAccessTenantBCampaignById() throws Exception {
        mockMvc.perform(get("/api/v1/sms/campaigns/" + campaignB.getId())
                        .header("Authorization", "Bearer " + tokenAAdmin))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("3. Spoofed X-Tenant-ID header is ignored and cannot leak Tenant B campaigns")
    void testSpoofedXTenantIdHeaderIgnored() throws Exception {
        mockMvc.perform(get("/api/v1/sms/campaigns")
                        .header("Authorization", "Bearer " + tokenAAdmin)
                        .header("X-Tenant-ID", tenantB.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name", is("Tenant A SMS Campaign")))
                .andExpect(jsonPath("$[0].id", is(campaignA.getId().toString())));
    }

    @Test
    @DisplayName("4. Spoofed businessId query parameter is ignored and cannot leak Tenant B campaigns")
    void testSpoofedBusinessIdQueryParamIgnored() throws Exception {
        mockMvc.perform(get("/api/v1/sms/campaigns")
                        .header("Authorization", "Bearer " + tokenAAdmin)
                        .param("businessId", tenantB.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name", is("Tenant A SMS Campaign")))
                .andExpect(jsonPath("$[0].id", is(campaignA.getId().toString())));
    }

    @Test
    @DisplayName("5. Tenant A attempting to create records under Tenant B is constrained to Tenant A")
    void testTenantACannotCreateRecordsUnderTenantB() throws Exception {
        CreateCampaignRequest req = new CreateCampaignRequest();
        req.setName("Malicious Tenant A Campaign");
        req.setPhoneNumbers(List.of("+1234567890"));

        mockMvc.perform(post("/api/v1/sms/campaigns")
                        .header("Authorization", "Bearer " + tokenAAdmin)
                        .header("X-Tenant-ID", tenantB.getId().toString())
                        .param("businessId", tenantB.getId().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Malicious Tenant A Campaign")));

        // Verify in DB that the campaign was created under Tenant A, NOT Tenant B
        TenantContext.setAdminMode(true);
        try {
            List<SmsCampaign> bCampaigns = smsCampaignRepository.findByTenant_Id(tenantB.getId());
            assertEquals(1, bCampaigns.size());
            assertEquals("Tenant B SMS Campaign", bCampaigns.get(0).getName());

            List<SmsCampaign> aCampaigns = smsCampaignRepository.findByTenant_Id(tenantA.getId());
            assertEquals(2, aCampaigns.size());
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    @DisplayName("6. Tenant A attempting to trigger Tenant B campaign receives 404 and campaign B is untouched")
    void testTenantACannotTriggerTenantBCampaign() throws Exception {
        mockMvc.perform(post("/api/v1/sms/campaigns/" + campaignB.getId() + "/trigger")
                        .header("Authorization", "Bearer " + tokenAAdmin))
                .andExpect(status().isNotFound());

        SmsCampaign refreshedB = smsCampaignRepository.findById(campaignB.getId()).orElseThrow();
        assertEquals("DRAFT", refreshedB.getStatus(), "Tenant B campaign status must remain DRAFT");
    }

    @Test
    @DisplayName("7. Unauthorized agent without MODULE_CAMPAIGNS permission receives 403 Forbidden")
    void testAgentWithoutPermissionForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/sms/campaigns")
                        .header("Authorization", "Bearer " + tokenAAgentNoPerm))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("8. Platform Admin legitimate access path allows targeting Tenant B")
    void testPlatformAdminLegitimateAccessPath() throws Exception {
        mockMvc.perform(get("/api/v1/sms/campaigns")
                        .header("Authorization", "Bearer " + tokenSuperAdmin)
                        .header("X-Tenant-ID", tenantB.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name", is("Tenant B SMS Campaign")))
                .andExpect(jsonPath("$[0].id", is(campaignB.getId().toString())));
    }
}
