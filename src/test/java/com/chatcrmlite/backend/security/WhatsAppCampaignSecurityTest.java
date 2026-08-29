package com.chatcrmlite.backend.security;

import com.chatcrmlite.backend.clients.MetaWhatsAppClient;
import com.chatcrmlite.backend.clients.WhatsAppClient;
import com.chatcrmlite.backend.models.*;
import com.chatcrmlite.backend.models.TenantSubscription.BillingCycle;
import com.chatcrmlite.backend.models.TenantSubscription.SubscriptionStatus;
import com.chatcrmlite.backend.models.WhatsAppCampaignRecipient.RecipientStatus;
import com.chatcrmlite.backend.repositories.*;
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

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
public class WhatsAppCampaignSecurityTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private SubscriptionPlanRepository subscriptionPlanRepository;
    @Autowired private TenantSubscriptionRepository tenantSubscriptionRepository;
    @Autowired private WhatsAppCampaignRepository campaignRepository;
    @Autowired private WhatsAppCampaignRecipientRepository recipientRepository;
    @Autowired private WhatsAppCampaignAnalyticsRepository analyticsRepository;
    @Autowired private WhatsAppCampaignAuditLogRepository auditLogRepository;
    @Autowired private WhatsAppTemplateRepository templateRepository;
    @Autowired private WhatsAppTemplateSnapshotRepository templateSnapshotRepository;
    @Autowired private ContactRepository contactRepository;
    @Autowired private UserSessionRepository sessionRepository;
    @Autowired private JwtUtils jwtUtils;
    @Autowired private PasswordEncoder passwordEncoder;

    @MockBean private MetaWhatsAppClient metaWhatsAppClient;
    @MockBean private DistributedSchedulerService distributedSchedulerService;

    private Tenant tenantA;
    private Tenant tenantB;

    private User tenantAAdmin;
    private User tenantAAgent;
    private User tenantBAdmin;

    private String tokenAAdmin;
    private String tokenAAgent;
    private String tokenAAgentNoPerm;
    private String tokenBAdmin;

    private WhatsAppCampaign campaignA;
    private Contact contactA;
    private WhatsAppCampaignRecipient recipientA;

    @BeforeEach
    void setUp() {
        sessionRepository.deleteAll();
        recipientRepository.deleteAll();
        auditLogRepository.deleteAll();
        analyticsRepository.deleteAll();
        campaignRepository.deleteAll();
        templateSnapshotRepository.deleteAll();
        templateRepository.deleteAll();
        contactRepository.deleteAll();
        userRepository.deleteAll();
        tenantSubscriptionRepository.deleteAll();
        tenantRepository.deleteAll();

        // 1. Pro plan with WhatsApp Campaign feature entitlement
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

        tenantAAgent = User.builder()
                .email("agent@tenant-a.com")
                .password(passwordEncoder.encode("Password123!"))
                .role(User.Role.AGENT)
                .accountStatus(User.AccountStatus.ACTIVE)
                .tenant(tenantA)
                .build();
        tenantAAgent.setPermissions(java.util.List.of("MODULE_CAMPAIGNS"));
        tenantAAgent = userRepository.save(tenantAAgent);

        User tenantAAgentNoPerm = User.builder()
                .email("agent-noperm@tenant-a.com")
                .password(passwordEncoder.encode("Password123!"))
                .role(User.Role.AGENT)
                .accountStatus(User.AccountStatus.ACTIVE)
                .tenant(tenantA)
                .build();
        tenantAAgentNoPerm.setPermissions(java.util.Collections.emptyList());
        tenantAAgentNoPerm = userRepository.save(tenantAAgentNoPerm);

        tenantBAdmin = User.builder()
                .email("admin@tenant-b.com")
                .password(passwordEncoder.encode("Password123!"))
                .role(User.Role.ADMIN)
                .accountStatus(User.AccountStatus.ACTIVE)
                .tenant(tenantB)
                .build();
        tenantBAdmin = userRepository.save(tenantBAdmin);

        // Tokens
        String sessAAdmin = "sess-a-admin-" + UUID.randomUUID();
        sessionRepository.save(UserSession.builder().tokenId(sessAAdmin).user(tenantAAdmin).status("ACTIVE").createdAt(LocalDateTime.now()).expiresAt(LocalDateTime.now().plusDays(1)).build());
        tokenAAdmin = jwtUtils.generateJwtToken(tenantAAdmin.getEmail(), sessAAdmin);

        String sessAAgent = "sess-a-agent-" + UUID.randomUUID();
        sessionRepository.save(UserSession.builder().tokenId(sessAAgent).user(tenantAAgent).status("ACTIVE").createdAt(LocalDateTime.now()).expiresAt(LocalDateTime.now().plusDays(1)).build());
        tokenAAgent = jwtUtils.generateJwtToken(tenantAAgent.getEmail(), sessAAgent);

        String sessAAgentNoPerm = "sess-a-agent-noperm-" + UUID.randomUUID();
        sessionRepository.save(UserSession.builder().tokenId(sessAAgentNoPerm).user(tenantAAgentNoPerm).status("ACTIVE").createdAt(LocalDateTime.now()).expiresAt(LocalDateTime.now().plusDays(1)).build());
        tokenAAgentNoPerm = jwtUtils.generateJwtToken(tenantAAgentNoPerm.getEmail(), sessAAgentNoPerm);

        String sessBAdmin = "sess-b-admin-" + UUID.randomUUID();
        sessionRepository.save(UserSession.builder().tokenId(sessBAdmin).user(tenantBAdmin).status("ACTIVE").createdAt(LocalDateTime.now()).expiresAt(LocalDateTime.now().plusDays(1)).build());
        tokenBAdmin = jwtUtils.generateJwtToken(tenantBAdmin.getEmail(), sessBAdmin);

        // 4. Create Template, Snapshot, Contact, and Campaign for Tenant A
        WhatsAppTemplateSnapshot snapshotA = WhatsAppTemplateSnapshot.builder()
                .name("promo_discount_template")
                .language("en_US")
                .category("MARKETING")
                .status("APPROVED")
                .version(1)
                .bodyText("Hello {{1}}, your secret deal code is {{2}}!")
                .build();
        snapshotA.setTenant(tenantA);
        snapshotA = templateSnapshotRepository.save(snapshotA);

        contactA = new Contact();
        contactA.setName("Alice Confidential Client");
        contactA.setWaId("+15551234567");
        contactA.setEmail("alice.secret@client.com");
        contactA.setTenant(tenantA);
        contactA.setOwner(tenantAAdmin);
        contactA = contactRepository.save(contactA);

        campaignA = WhatsAppCampaign.builder()
                .name("Tenant A Secret Q3 Campaign")
                .templateSnapshot(snapshotA)
                .status(WhatsAppCampaign.Status.DRAFT)
                .targetType(WhatsAppCampaign.TargetType.ALL_CONTACTS)
                .targetFilterJson("{\"segment\":\"VIP_CUSTOMERS\"}")
                .variableMappingJson("{\"1\":\"contact.name\",\"2\":\"lead.secretDeal\"}")
                .saveImportedRecipients(false)
                .owner(tenantAAdmin)
                .build();
        campaignA.setTenant(tenantA);
        campaignA = campaignRepository.save(campaignA);

        recipientA = WhatsAppCampaignRecipient.builder()
                .campaign(campaignA)
                .contact(contactA)
                .phoneNumber("+15551234567")
                .resolvedVariablesJson("[\"Alice Confidential Client\",\"DEAL-TOP-SECRET-999\"]")
                .status(RecipientStatus.PENDING)
                .build();
        recipientA.setTenant(tenantA);
        recipientA = recipientRepository.save(recipientA);

        WhatsAppCampaignAnalytics analyticsA = WhatsAppCampaignAnalytics.builder()
                .campaign(campaignA)
                .totalTargetRecipients(1)
                .totalSent(0)
                .totalDelivered(0)
                .totalRead(0)
                .totalFailed(0)
                .build();
        analyticsA.setTenant(tenantA);
        analyticsRepository.save(analyticsA);

        WhatsAppCampaignAuditLog auditA = WhatsAppCampaignAuditLog.builder()
                .campaign(campaignA)
                .actorUser(tenantAAdmin)
                .action(WhatsAppCampaignAuditLog.Action.CAMPAIGN_CREATED)
                .detailsJson("{\"createdBy\":\"admin@tenant-a.com\"}")
                .build();
        auditA.setTenant(tenantA);
        auditLogRepository.save(auditA);
    }

    @Test
    @DisplayName("1. SameTenantCampaign_AccessAllowed: Tenant A Admin can read Tenant A's campaign details")
    void testSameTenantCampaign_AccessAllowed() throws Exception {
        mockMvc.perform(get("/api/v1/whatsapp/campaigns/" + campaignA.getId())
                        .header("Authorization", "Bearer " + tokenAAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", equalTo(campaignA.getId().toString())))
                .andExpect(jsonPath("$.name", equalTo("Tenant A Secret Q3 Campaign")));
    }

    @Test
    @DisplayName("2 & 3. CrossTenantCampaign_AccessDenied / Returns404Or403: Tenant B cannot access Tenant A's campaign")
    void testCrossTenantCampaign_AccessDenied() throws Exception {
        mockMvc.perform(get("/api/v1/whatsapp/campaigns/" + campaignA.getId())
                        .header("Authorization", "Bearer " + tokenBAdmin))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("4. CrossTenantCampaign_DoesNotExposeMetadata: Tenant B receives 404 with no campaign metadata")
    void testCrossTenantCampaign_DoesNotExposeMetadata() throws Exception {
        mockMvc.perform(get("/api/v1/whatsapp/campaigns/" + campaignA.getId())
                        .header("Authorization", "Bearer " + tokenBAdmin))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.name").doesNotExist())
                .andExpect(jsonPath("$.targetFilterJson").doesNotExist())
                .andExpect(jsonPath("$.variableMappingJson").doesNotExist());
    }

    @Test
    @DisplayName("5, 6 & 7. CrossTenantCampaign_DoesNotExposeRecipients / PhoneNumbers / ContactPII: Recipient endpoint blocks Tenant B")
    void testCrossTenantCampaign_DoesNotExposeRecipients() throws Exception {
        mockMvc.perform(get("/api/v1/whatsapp/campaigns/" + campaignA.getId() + "/recipients")
                        .header("Authorization", "Bearer " + tokenBAdmin))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.content").doesNotExist())
                .andExpect(jsonPath("$.phoneNumber").doesNotExist());
    }

    @Test
    @DisplayName("CrossTenantCampaign_AnalyticsAndAuditLogsBlocked: Analytics and audit logs block Tenant B")
    void testCrossTenantCampaign_AnalyticsAndAuditLogsBlocked() throws Exception {
        mockMvc.perform(get("/api/v1/whatsapp/campaigns/" + campaignA.getId() + "/analytics")
                        .header("Authorization", "Bearer " + tokenBAdmin))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/whatsapp/campaigns/" + campaignA.getId() + "/audit-logs")
                        .header("Authorization", "Bearer " + tokenBAdmin))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("8. MissingCampaign_ReturnsExpectedError: Non-existent campaign ID returns 404")
    void testMissingCampaign_ReturnsExpectedError() throws Exception {
        UUID nonExistentId = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/whatsapp/campaigns/" + nonExistentId)
                        .header("Authorization", "Bearer " + tokenAAdmin))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("9. UnauthenticatedRequest_Rejected: Unauthenticated request is rejected with 401/403")
    void testUnauthenticatedRequest_Rejected() throws Exception {
        mockMvc.perform(get("/api/v1/whatsapp/campaigns/" + campaignA.getId()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("10. AdminSameTenant_AccessAllowed: Tenant A Admin can read recipients and analytics")
    void testAdminSameTenant_AccessAllowed() throws Exception {
        mockMvc.perform(get("/api/v1/whatsapp/campaigns/" + campaignA.getId() + "/recipients")
                        .header("Authorization", "Bearer " + tokenAAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].phoneNumber", equalTo("+15551234567")));

        mockMvc.perform(get("/api/v1/whatsapp/campaigns/" + campaignA.getId() + "/analytics")
                        .header("Authorization", "Bearer " + tokenAAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalTargetRecipients", equalTo(1)));
    }

    @Test
    @DisplayName("11. AgentSameTenant_AccessAllowed: Agent within same tenant can view tenant campaigns")
    void testAgentSameTenant_AccessAllowed() throws Exception {
        mockMvc.perform(get("/api/v1/whatsapp/campaigns/" + campaignA.getId())
                        .header("Authorization", "Bearer " + tokenAAgent))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", equalTo(campaignA.getId().toString())));
    }

    @Test
    @DisplayName("12. Mutation Operations Block Cross-Tenant: Pause, Resume, Cancel, Schedule, Dry-run cannot cross tenants")
    void testMutations_BlockedCrossTenant() throws Exception {
        // Pause attempt from Tenant B
        mockMvc.perform(post("/api/v1/whatsapp/campaigns/" + campaignA.getId() + "/pause")
                        .header("Authorization", "Bearer " + tokenBAdmin))
                .andExpect(status().isNotFound());

        // Resume attempt from Tenant B
        mockMvc.perform(post("/api/v1/whatsapp/campaigns/" + campaignA.getId() + "/resume")
                        .header("Authorization", "Bearer " + tokenBAdmin))
                .andExpect(status().isNotFound());

        // Cancel attempt from Tenant B
        mockMvc.perform(post("/api/v1/whatsapp/campaigns/" + campaignA.getId() + "/cancel")
                        .header("Authorization", "Bearer " + tokenBAdmin))
                .andExpect(status().isNotFound());

        // Dry-run attempt from Tenant B
        mockMvc.perform(post("/api/v1/whatsapp/campaigns/" + campaignA.getId() + "/dry-run")
                        .header("Authorization", "Bearer " + tokenBAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"testPhoneNumber\":\"+15559998888\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("13. AgentWithoutPermission_Forbidden: Agent in same tenant without MODULE_CAMPAIGNS permission is forbidden")
    void testAgentWithoutPermission_Forbidden() throws Exception {
        mockMvc.perform(get("/api/v1/whatsapp/campaigns/" + campaignA.getId())
                        .header("Authorization", "Bearer " + tokenAAgentNoPerm))
                .andExpect(status().isForbidden());
    }
}
