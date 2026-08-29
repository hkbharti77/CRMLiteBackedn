package com.chatcrmlite.backend.security;

import com.chatcrmlite.backend.clients.MetaFlowClient;
import com.chatcrmlite.backend.models.*;
import com.chatcrmlite.backend.models.TenantSubscription.BillingCycle;
import com.chatcrmlite.backend.models.TenantSubscription.SubscriptionStatus;
import com.chatcrmlite.backend.models.flows.*;
import com.chatcrmlite.backend.repositories.*;
import com.chatcrmlite.backend.repositories.flows.*;
import com.chatcrmlite.backend.services.DistributedSchedulerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * P3-03-02 Security Test — WhatsApp Flow Audit Logs Cross-Tenant IDOR
 *
 * Verifies that GET /api/v1/whatsapp-flows/{id}/audit-logs enforces strict
 * tenant isolation:
 *   - Tenant A user + Tenant A flow  → 200 OK, data returned
 *   - Tenant A user + Tenant B flow  → 404, no data leaked
 *   - Tenant B user + Tenant A flow  → 404, no data leaked
 *   - Unauthenticated               → 401/403
 *   - Non-existent flow UUID        → 404
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
public class WhatsAppFlowAuditLogSecurityTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private SubscriptionPlanRepository subscriptionPlanRepository;
    @Autowired private TenantSubscriptionRepository tenantSubscriptionRepository;
    @Autowired private UserSessionRepository sessionRepository;
    @Autowired private JwtUtils jwtUtils;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private WhatsAppFlowRepository flowRepository;
    @Autowired private WhatsAppFlowAuditLogRepository auditLogRepository;

    // Spy on auditLogRepository so we can verify it is never queried on denied requests
    @SpyBean private WhatsAppFlowAuditLogRepository auditLogRepositorySpy;

    @MockBean private MetaFlowClient metaFlowClient;
    @MockBean private DistributedSchedulerService distributedSchedulerService;

    // ── Tenant A ────────────────────────────────────────────────────────────
    private Tenant tenantA;
    private User   tenantAAdmin;
    private String tokenAAdmin;

    // ── Tenant B ────────────────────────────────────────────────────────────
    private Tenant tenantB;
    private User   tenantBAdmin;
    private String tokenBAdmin;

    // ── Data ────────────────────────────────────────────────────────────────
    private WhatsAppFlow flowA;             // owned by Tenant A
    private WhatsAppFlowAuditLog auditLogA; // belongs to flowA / Tenant A

    @BeforeEach
    void setUp() {
        // Purge in dependency order
        sessionRepository.deleteAll();
        auditLogRepository.deleteAll();
        flowRepository.deleteAll();
        userRepository.deleteAll();
        tenantSubscriptionRepository.deleteAll();
        tenantRepository.deleteAll();

        // ── Subscription plan ──────────────────────────────────────────────
        SubscriptionPlan proPlan = subscriptionPlanRepository.findById("PRO").orElseGet(() -> {
            SubscriptionPlan p = new SubscriptionPlan(
                    "PRO", "Pro Pack",
                    BigDecimal.valueOf(2999), BigDecimal.valueOf(28790),
                    10, 1_000_000, 1_000_000, 1_000_000, 25_000,
                    true, true, true);
            return subscriptionPlanRepository.save(p);
        });

        // ── Tenants ────────────────────────────────────────────────────────
        tenantA = tenantRepository.save(
                Tenant.builder().businessName("Audit Log Security Tenant A").businessType("retail").build());
        tenantB = tenantRepository.save(
                Tenant.builder().businessName("Audit Log Security Tenant B").businessType("tech").build());

        createSubscription(tenantA, proPlan);
        createSubscription(tenantB, proPlan);

        // ── Users ──────────────────────────────────────────────────────────
        tenantAAdmin = userRepository.save(User.builder()
                .email("audit-admin@tenant-a.com")
                .password(passwordEncoder.encode("Password123!"))
                .role(User.Role.ADMIN)
                .accountStatus(User.AccountStatus.ACTIVE)
                .tenant(tenantA)
                .build());

        tenantBAdmin = userRepository.save(User.builder()
                .email("audit-admin@tenant-b.com")
                .password(passwordEncoder.encode("Password123!"))
                .role(User.Role.ADMIN)
                .accountStatus(User.AccountStatus.ACTIVE)
                .tenant(tenantB)
                .build());

        // ── JWT sessions ───────────────────────────────────────────────────
        tokenAAdmin = mintToken(tenantAAdmin);
        tokenBAdmin = mintToken(tenantBAdmin);

        // ── WhatsApp Flow owned by Tenant A ────────────────────────────────
        flowA = WhatsAppFlow.builder()
                .name("Tenant A Onboarding Flow")
                .category(FlowCategory.CUSTOMER_SUPPORT)
                .status(FlowLifecycleStatus.DRAFT)
                .wabaId("waba-audit-a-001")
                .phoneNumberId("phone-audit-a-001")
                .build();
        flowA.setTenant(tenantA);
        flowA = flowRepository.save(flowA);

        // ── Audit Log belonging to flowA / Tenant A ────────────────────────
        auditLogA = WhatsAppFlowAuditLog.builder()
                .flowId(flowA.getId())
                .actorId(tenantAAdmin.getId())
                .action(FlowAuditAction.FLOW_CREATED)
                .metadataJson("{\"source\":\"API\"}")
                .build();
        auditLogA.setTenant(tenantA);
        auditLogA = auditLogRepository.save(auditLogA);
    }

    // ── Test 1: Same-tenant access is allowed ─────────────────────────────

    @Test
    @DisplayName("1. getAuditLogs_SameTenantFlow_ReturnsLogs: Tenant A user can read Tenant A's flow audit logs")
    void getAuditLogs_SameTenantFlow_ReturnsLogs() throws Exception {
        mockMvc.perform(get("/api/v1/whatsapp-flows/" + flowA.getId() + "/audit-logs")
                        .header("Authorization", "Bearer " + tokenAAdmin))
                .andExpect(status().isOk())
                // Returns list containing the audit log
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))))
                // Verify action exists in the output
                .andExpect(jsonPath("$[0].action", equalTo("FLOW_CREATED")));
    }

    // ── Test 2: Cross-tenant access is denied ─────────────────────────────

    @Test
    @DisplayName("2. getAuditLogs_CrossTenantFlow_Returns404: Tenant B cannot retrieve Tenant A's flow audit logs")
    void getAuditLogs_CrossTenantFlow_Returns404() throws Exception {
        mockMvc.perform(get("/api/v1/whatsapp-flows/" + flowA.getId() + "/audit-logs")
                        .header("Authorization", "Bearer " + tokenBAdmin))
                // Must be 404 (fail-closed)
                .andExpect(status().isNotFound())
                // No metadata/logs leaked
                .andExpect(jsonPath("$.action").doesNotExist())
                .andExpect(jsonPath("$.actorId").doesNotExist());
    }

    @Test
    @DisplayName("3. getAuditLogs_CrossTenantFlow_RepositoryNeverQueriedForUnauthorizedFlow")
    void getAuditLogs_CrossTenantFlow_RepositoryNeverQueriedForUnauthorizedFlow() throws Exception {
        mockMvc.perform(get("/api/v1/whatsapp-flows/" + flowA.getId() + "/audit-logs")
                        .header("Authorization", "Bearer " + tokenBAdmin))
                .andExpect(status().isNotFound());

        // The audit log repository must NEVER be queried when tenant authorization fails.
        verify(auditLogRepositorySpy, never())
                .findAllByFlowIdOrderByCreatedAtDesc(flowA.getId());
    }

    // ── Test 3: Unauthenticated request is rejected ───────────────────────

    @Test
    @DisplayName("4. getAuditLogs_Unauthenticated_ReturnsUnauthorized")
    void getAuditLogs_Unauthenticated_ReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/whatsapp-flows/" + flowA.getId() + "/audit-logs"))
                .andExpect(status().is(anyOf(equalTo(401), equalTo(403))));
    }

    // ── Test 4: Non-existent flow UUID returns 404 ────────────────────────

    @Test
    @DisplayName("5. getAuditLogs_MissingFlow_Returns404")
    void getAuditLogs_MissingFlow_Returns404() throws Exception {
        UUID nonExistentFlowId = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/whatsapp-flows/" + nonExistentFlowId + "/audit-logs")
                        .header("Authorization", "Bearer " + tokenAAdmin))
                .andExpect(status().isNotFound());
    }

    // ── Test 5: Null/missing tenant in JWT fails closed ───────────────────
    @Test
    @DisplayName("6. getAuditLogs_NullTenant_FailsClosed")
    void getAuditLogs_NullTenant_FailsClosed() throws Exception {
        // Create an un-tenantized platform super admin or a malformed user
        User noTenantUser = userRepository.save(User.builder()
                .email("no-tenant@example.com")
                .password(passwordEncoder.encode("Password123!"))
                .role(User.Role.SUPER_ADMIN)
                .accountStatus(User.AccountStatus.ACTIVE)
                .build()); // no tenant set

        String tokenNoTenant = mintToken(noTenantUser);

        // When flowService attempts to extract getTenantId(user), it will throw IllegalStateException
        // Which the global exception handler translates (usually to a 500 or 401/403).
        // Let's assert that it fails and definitely does not return 200.
        mockMvc.perform(get("/api/v1/whatsapp-flows/" + flowA.getId() + "/audit-logs")
                        .header("Authorization", "Bearer " + tokenNoTenant))
                .andExpect(status().is(not(equalTo(200))));
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private void createSubscription(Tenant tenant, SubscriptionPlan plan) {
        TenantSubscription sub = new TenantSubscription();
        sub.setTenant(tenant);
        sub.setPlan(plan);
        sub.setBillingCycle(BillingCycle.MONTHLY);
        sub.setStatus(SubscriptionStatus.ACTIVE);
        sub.setCurrentPeriodStart(LocalDateTime.now());
        sub.setCurrentPeriodEnd(LocalDateTime.now().plusMonths(1));
        tenantSubscriptionRepository.save(sub);
    }

    private String mintToken(User user) {
        String sessId = "sess-audit-" + UUID.randomUUID();
        sessionRepository.save(UserSession.builder()
                .tokenId(sessId)
                .user(user)
                .status("ACTIVE")
                .createdAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusDays(1))
                .build());
        return jwtUtils.generateJwtToken(user.getEmail(), sessId);
    }

    private static org.hamcrest.Matcher<Integer> anyOf(
            org.hamcrest.Matcher<Integer> m1, org.hamcrest.Matcher<Integer> m2) {
        return org.hamcrest.Matchers.anyOf(m1, m2);
    }
}
