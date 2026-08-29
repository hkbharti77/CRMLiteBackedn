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
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * P3-03-01 Security Test — WhatsApp Flow Submissions Cross-Tenant IDOR
 *
 * Verifies that GET /api/v1/whatsapp-flows/{id}/submissions enforces strict
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
public class WhatsAppFlowSubmissionSecurityTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private SubscriptionPlanRepository subscriptionPlanRepository;
    @Autowired private TenantSubscriptionRepository tenantSubscriptionRepository;
    @Autowired private UserSessionRepository sessionRepository;
    @Autowired private JwtUtils jwtUtils;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private WhatsAppFlowRepository flowRepository;
    @Autowired private FlowSubmissionRepository submissionRepository;
    @Autowired private ContactRepository contactRepository;

    // Spy on submissionRepository so we can verify it is never queried on denied requests
    @SpyBean private FlowSubmissionRepository submissionRepositorySpy;

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
    private WhatsAppFlow    flowA;        // owned by Tenant A
    private FlowSubmission  submissionA1; // belongs to flowA / Tenant A

    @BeforeEach
    void setUp() {
        // Purge in dependency order
        sessionRepository.deleteAll();
        submissionRepository.deleteAll();
        flowRepository.deleteAll();
        contactRepository.deleteAll();
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
                Tenant.builder().businessName("Flow Security Tenant A").businessType("retail").build());
        tenantB = tenantRepository.save(
                Tenant.builder().businessName("Flow Security Tenant B").businessType("tech").build());

        createSubscription(tenantA, proPlan);
        createSubscription(tenantB, proPlan);

        // ── Users ──────────────────────────────────────────────────────────
        tenantAAdmin = userRepository.save(User.builder()
                .email("flow-admin@tenant-a.com")
                .password(passwordEncoder.encode("Password123!"))
                .role(User.Role.ADMIN)
                .accountStatus(User.AccountStatus.ACTIVE)
                .tenant(tenantA)
                .build());

        tenantBAdmin = userRepository.save(User.builder()
                .email("flow-admin@tenant-b.com")
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
                .name("Tenant A Lead Capture Flow")
                .category(FlowCategory.LEAD_GENERATION)
                .status(FlowLifecycleStatus.DRAFT)
                .wabaId("waba-tenant-a-001")
                .phoneNumberId("phone-tenant-a-001")
                .build();
        flowA.setTenant(tenantA);
        flowA = flowRepository.save(flowA);

        // ── Flow submission belonging to flowA / Tenant A ──────────────────
        // Contains customer PII that Tenant B must NEVER see
        Contact contactA = new Contact();
        contactA.setName("Alice Confidential");
        contactA.setWaId("+15550001111");
        contactA.setEmail("alice.confidential@example.com");
        contactA.setTenant(tenantA);
        contactA.setOwner(tenantAAdmin);
        contactA = contactRepository.save(contactA);

        submissionA1 = FlowSubmission.builder()
                .eventId("wa-event-" + UUID.randomUUID())
                .flow(flowA)
                .metaFlowId("meta-flow-a-001")
                .contact(contactA)
                .customerPhone("+15550001111")
                .rawResponseJson("{\"name\":\"Alice Confidential\",\"email\":\"alice.confidential@example.com\"}")
                .normalizedDataJson("{\"lead_name\":\"Alice Confidential\"}")
                .processingStatus(SubmissionProcessingStatus.RECEIVED)
                .build();
        submissionA1.setTenant(tenantA);
        submissionA1 = submissionRepository.save(submissionA1);
    }

    // ── Test 1: Same-tenant access is allowed ─────────────────────────────

    @Test
    @DisplayName("1. getSubmissions_SameTenantFlow_ReturnsSubmissions: Tenant A user can read Tenant A's flow submissions")
    void getSubmissions_SameTenantFlow_ReturnsSubmissions() throws Exception {
        mockMvc.perform(get("/api/v1/whatsapp-flows/" + flowA.getId() + "/submissions")
                        .header("Authorization", "Bearer " + tokenAAdmin))
                .andExpect(status().isOk())
                // Returns list containing the submission
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))))
                // Customer phone is present for authorized tenant
                .andExpect(jsonPath("$[0].customerPhone", equalTo("+15550001111")));
    }

    // ── Test 2: Cross-tenant access is denied ─────────────────────────────

    @Test
    @DisplayName("2. getSubmissions_CrossTenantFlow_Returns404Or403: Tenant B cannot retrieve Tenant A's flow submissions")
    void getSubmissions_CrossTenantFlow_Returns404Or403() throws Exception {
        mockMvc.perform(get("/api/v1/whatsapp-flows/" + flowA.getId() + "/submissions")
                        .header("Authorization", "Bearer " + tokenBAdmin))
                // Must be 404 (fail-closed — tenant B cannot distinguish "not my tenant" from "not found")
                .andExpect(status().isNotFound())
                // Absolutely no PII or submission data in the response body
                .andExpect(jsonPath("$.customerPhone").doesNotExist())
                .andExpect(jsonPath("$.rawResponseJson").doesNotExist())
                .andExpect(jsonPath("$.contact").doesNotExist());
    }

    @Test
    @DisplayName("2b. getSubmissions_CrossTenantFlow_RepositoryNeverQueriedForUnauthorizedFlow: submission repo not called for denied flows")
    void getSubmissions_CrossTenantFlow_RepositoryNeverQueriedForUnauthorizedFlow() throws Exception {
        mockMvc.perform(get("/api/v1/whatsapp-flows/" + flowA.getId() + "/submissions")
                        .header("Authorization", "Bearer " + tokenBAdmin))
                .andExpect(status().isNotFound());

        // The submission repository must NEVER be queried when tenant authorization fails.
        // flowService.getFlow() should reject the request BEFORE the repository is touched.
        verify(submissionRepositorySpy, never())
                .findAllByFlowIdOrderByCreatedAtDesc(flowA.getId());
    }

    // ── Test 3: Unauthenticated request is rejected ───────────────────────

    @Test
    @DisplayName("3. getSubmissions_Unauthenticated_ReturnsUnauthorized: No token → 401 or 403")
    void getSubmissions_Unauthenticated_ReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/whatsapp-flows/" + flowA.getId() + "/submissions"))
                // Spring Security returns 403 for anonymous users by default; 401 is also acceptable
                .andExpect(status().is(anyOf(equalTo(401), equalTo(403))));
    }

    // ── Test 4: Non-existent flow UUID returns 404 ────────────────────────

    @Test
    @DisplayName("4. getSubmissions_MissingFlow_Returns404: Random UUID returns 404")
    void getSubmissions_MissingFlow_Returns404() throws Exception {
        UUID nonExistentFlowId = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/whatsapp-flows/" + nonExistentFlowId + "/submissions")
                        .header("Authorization", "Bearer " + tokenAAdmin))
                .andExpect(status().isNotFound());
    }

    // ── Test 5: Tenant A user + Tenant B flow is also denied ─────────────
    // (Symmetric invariant: the isolation must be bidirectional)

    @Test
    @DisplayName("5. getSubmissions_TenantAUserTenantBFlow_Returns404: Tenant A cannot access Tenant B flows")
    void getSubmissions_TenantAUserTenantBFlow_Returns404() throws Exception {
        // Create a flow owned by Tenant B
        WhatsAppFlow flowB = WhatsAppFlow.builder()
                .name("Tenant B Flow")
                .category(FlowCategory.LEAD_GENERATION)
                .status(FlowLifecycleStatus.DRAFT)
                .wabaId("waba-tenant-b-001")
                .phoneNumberId("phone-tenant-b-001")
                .build();
        flowB.setTenant(tenantB);
        flowB = flowRepository.save(flowB);

        mockMvc.perform(get("/api/v1/whatsapp-flows/" + flowB.getId() + "/submissions")
                        .header("Authorization", "Bearer " + tokenAAdmin))
                .andExpect(status().isNotFound());
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
        String sessId = "sess-flow-" + UUID.randomUUID();
        sessionRepository.save(UserSession.builder()
                .tokenId(sessId)
                .user(user)
                .status("ACTIVE")
                .createdAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusDays(1))
                .build());
        return jwtUtils.generateJwtToken(user.getEmail(), sessId);
    }

    /** Hamcrest matcher that passes if the actual int equals any of the given values. */
    private static org.hamcrest.Matcher<Integer> anyOf(
            org.hamcrest.Matcher<Integer> m1, org.hamcrest.Matcher<Integer> m2) {
        return org.hamcrest.Matchers.anyOf(m1, m2);
    }
}
