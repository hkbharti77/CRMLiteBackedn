package com.chatcrmlite.backend.security;

import com.chatcrmlite.backend.clients.MetaWhatsAppClient;
import com.chatcrmlite.backend.dto.whatsapp.calling.InitiateWhatsAppCallRequest;
import com.chatcrmlite.backend.dto.whatsapp.calling.RequestCallPermissionRequest;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.SubscriptionPlan;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.TenantSubscription;
import com.chatcrmlite.backend.models.TenantSubscription.BillingCycle;
import com.chatcrmlite.backend.models.TenantSubscription.SubscriptionStatus;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.UserSession;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.models.whatsapp.WhatsAppCallPermission;
import com.chatcrmlite.backend.models.whatsapp.WhatsAppCallSession;
import com.chatcrmlite.backend.repositories.*;
import com.chatcrmlite.backend.services.DistributedSchedulerService;
import com.chatcrmlite.backend.services.voice.WhatsAppWebRtcMediaGateway;
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
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = com.chatcrmlite.backend.ChatCrmBackendApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
public class WhatsAppCallingApiSecurityIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private SubscriptionPlanRepository subscriptionPlanRepository;
    @Autowired private TenantSubscriptionRepository tenantSubscriptionRepository;
    @Autowired private UserSessionRepository sessionRepository;
    @Autowired private WhatsAppConfigRepository configRepository;
    @Autowired private WhatsAppCallSessionRepository callSessionRepository;
    @Autowired private WhatsAppCallPermissionRepository permissionRepository;
    @Autowired private ContactRepository contactRepository;
    @Autowired private JwtUtils jwtUtils;
    @Autowired private PasswordEncoder passwordEncoder;

    @MockBean private MetaWhatsAppClient metaWhatsAppClient;
    @MockBean private WhatsAppWebRtcMediaGateway mediaGateway;
    @MockBean private DistributedSchedulerService distributedSchedulerService;
    @MockBean private com.chatcrmlite.backend.services.storage.CloudinaryStorageService cloudinaryStorageService;

    private Tenant tenantA;
    private Tenant tenantB;

    private User tenantAAdmin;
    private User tenantAAgentNoPerm;
    private User tenantBAdmin;

    private String tokenAAdmin;
    private String tokenAAgentNoPerm;
    private String tokenBAdmin;

    private WhatsAppCallSession callSessionA;
    private WhatsAppCallSession callSessionB;

    @BeforeEach
    void setUp() {
        sessionRepository.deleteAll();
        callSessionRepository.deleteAll();
        permissionRepository.deleteAll();
        contactRepository.deleteAll();
        configRepository.deleteAll();
        userRepository.deleteAll();
        tenantSubscriptionRepository.deleteAll();
        tenantRepository.deleteAll();

        // 1. Subscription Plan
        SubscriptionPlan enterprisePlan = subscriptionPlanRepository.findById("ENTERPRISE").orElseGet(() -> {
            SubscriptionPlan plan = new SubscriptionPlan("ENTERPRISE", "Enterprise Pack", BigDecimal.valueOf(4999), BigDecimal.valueOf(47990), 20, 1000000, 1000000, 1000000, 50000, true, true, true);
            return subscriptionPlanRepository.save(plan);
        });

        // 2. Tenants
        tenantA = Tenant.builder().businessName("Alpha Corp").businessType("tech").build();
        tenantB = Tenant.builder().businessName("Beta Corp").businessType("retail").build();
        tenantA = tenantRepository.save(tenantA);
        tenantB = tenantRepository.save(tenantB);

        // Subscriptions
        TenantSubscription subA = new TenantSubscription();
        subA.setTenant(tenantA);
        subA.setPlan(enterprisePlan);
        subA.setBillingCycle(BillingCycle.MONTHLY);
        subA.setStatus(SubscriptionStatus.ACTIVE);
        subA.setCurrentPeriodStart(LocalDateTime.now());
        subA.setCurrentPeriodEnd(LocalDateTime.now().plusMonths(1));
        tenantSubscriptionRepository.save(subA);

        TenantSubscription subB = new TenantSubscription();
        subB.setTenant(tenantB);
        subB.setPlan(enterprisePlan);
        subB.setBillingCycle(BillingCycle.MONTHLY);
        subB.setStatus(SubscriptionStatus.ACTIVE);
        subB.setCurrentPeriodStart(LocalDateTime.now());
        subB.setCurrentPeriodEnd(LocalDateTime.now().plusMonths(1));
        tenantSubscriptionRepository.save(subB);

        // 3. WhatsApp Configs
        WhatsAppConfig cfgA = WhatsAppConfig.builder()
                .phoneNumberId("phone_alpha_01")
                .wabaId("waba_alpha_01")
                .accessToken("token_alpha_fake")
                .build();
        cfgA.setTenant(tenantA);
        configRepository.save(cfgA);

        WhatsAppConfig cfgB = WhatsAppConfig.builder()
                .phoneNumberId("phone_beta_01")
                .wabaId("waba_beta_01")
                .accessToken("token_beta_fake")
                .build();
        cfgB.setTenant(tenantB);
        configRepository.save(cfgB);

        // 4. Users
        tenantAAdmin = User.builder()
                .email("admin@alpha.com")
                .password(passwordEncoder.encode("Password123!"))
                .role(User.Role.ADMIN)
                .accountStatus(User.AccountStatus.ACTIVE)
                .tenant(tenantA)
                .build();
        tenantAAdmin = userRepository.save(tenantAAdmin);

        tenantAAgentNoPerm = User.builder()
                .email("agent@alpha.com")
                .password(passwordEncoder.encode("Password123!"))
                .role(User.Role.AGENT)
                .accountStatus(User.AccountStatus.ACTIVE)
                .tenant(tenantA)
                .build();
        tenantAAgentNoPerm.setPermissions(Collections.emptyList());
        tenantAAgentNoPerm = userRepository.save(tenantAAgentNoPerm);

        tenantBAdmin = User.builder()
                .email("admin@beta.com")
                .password(passwordEncoder.encode("Password123!"))
                .role(User.Role.ADMIN)
                .accountStatus(User.AccountStatus.ACTIVE)
                .tenant(tenantB)
                .build();
        tenantBAdmin = userRepository.save(tenantBAdmin);

        // Sessions & JWT Tokens
        String sessAAdmin = "sess-a-adm-" + UUID.randomUUID();
        sessionRepository.save(UserSession.builder().tokenId(sessAAdmin).user(tenantAAdmin).status("ACTIVE").createdAt(LocalDateTime.now()).expiresAt(LocalDateTime.now().plusDays(1)).build());
        tokenAAdmin = jwtUtils.generateJwtToken(tenantAAdmin.getEmail(), sessAAdmin);

        String sessAAgent = "sess-a-agt-" + UUID.randomUUID();
        sessionRepository.save(UserSession.builder().tokenId(sessAAgent).user(tenantAAgentNoPerm).status("ACTIVE").createdAt(LocalDateTime.now()).expiresAt(LocalDateTime.now().plusDays(1)).build());
        tokenAAgentNoPerm = jwtUtils.generateJwtToken(tenantAAgentNoPerm.getEmail(), sessAAgent);

        String sessBAdmin = "sess-b-adm-" + UUID.randomUUID();
        sessionRepository.save(UserSession.builder().tokenId(sessBAdmin).user(tenantBAdmin).status("ACTIVE").createdAt(LocalDateTime.now()).expiresAt(LocalDateTime.now().plusDays(1)).build());
        tokenBAdmin = jwtUtils.generateJwtToken(tenantBAdmin.getEmail(), sessBAdmin);

        // 5. Call Sessions
        callSessionA = WhatsAppCallSession.builder()
                .callId("wa_call_alpha_101")
                .direction("BUSINESS_INITIATED")
                .fromWaId("phone_alpha_01")
                .toWaId("919876543210")
                .phoneNumberId("phone_alpha_01")
                .wabaId("waba_alpha_01")
                .state("ENDED")
                .startedAt(Instant.now().minusSeconds(120))
                .connectedAt(Instant.now().minusSeconds(110))
                .endedAt(Instant.now().minusSeconds(10))
                .durationSeconds(100)
                .createdAt(Instant.now().minusSeconds(120))
                .build();
        callSessionA.setTenant(tenantA);
        callSessionA = callSessionRepository.save(callSessionA);

        callSessionB = WhatsAppCallSession.builder()
                .callId("wa_call_beta_202")
                .direction("USER_INITIATED")
                .fromWaId("919999999999")
                .toWaId("phone_beta_01")
                .phoneNumberId("phone_beta_01")
                .wabaId("waba_beta_01")
                .state("ACTIVE")
                .startedAt(Instant.now().minusSeconds(60))
                .connectedAt(Instant.now().minusSeconds(50))
                .createdAt(Instant.now().minusSeconds(60))
                .build();
        callSessionB.setTenant(tenantB);
        callSessionB = callSessionRepository.save(callSessionB);
    }

    // ──────────────── 1. Authentication Tests ────────────────

    @Test
    @DisplayName("Auth: Unauthenticated requests to all calling endpoints must be rejected (403 Forbidden)")
    void testUnauthenticatedRequests_Return401() throws Exception {
        mockMvc.perform(post("/api/v1/voice/whatsapp/calls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/voice/whatsapp/calls"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/voice/whatsapp/calls/any_call_id"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/voice/whatsapp/calls/any_call_id/turns"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/voice/whatsapp/analytics"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/voice/whatsapp/permissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/voice/whatsapp/permissions/any_contact"))
                .andExpect(status().isForbidden());
    }

    // ──────────────── 2. Tenant Isolation Tests ────────────────

    @Test
    @DisplayName("Tenant Isolation: Tenant A cannot access Tenant B's call detail -> returns 404 without leaking Tenant B existence")
    void testTenantIsolation_CannotAccessOtherTenantCall() throws Exception {
        mockMvc.perform(get("/api/v1/voice/whatsapp/calls/" + callSessionB.getCallId())
                        .header("Authorization", "Bearer " + tokenAAdmin))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", is("Not Found")))
                .andExpect(jsonPath("$.message", containsString("Call not found")));
    }

    @Test
    @DisplayName("Tenant Isolation: Tenant A can access its own call detail")
    void testTenantIsolation_CanAccessOwnCall() throws Exception {
        mockMvc.perform(get("/api/v1/voice/whatsapp/calls/" + callSessionA.getCallId())
                        .header("Authorization", "Bearer " + tokenAAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.callId", is(callSessionA.getCallId())))
                .andExpect(jsonPath("$.phoneNumberId", is("phone_alpha_01")))
                .andExpect(jsonPath("$.state", is("ENDED")))
                .andExpect(jsonPath("$.durationSeconds", is(100)));
    }

    @Test
    @DisplayName("Tenant Isolation: Tenant A cannot access Tenant B's call turns -> returns 404")
    void testTenantIsolation_CannotAccessOtherTenantTurns() throws Exception {
        mockMvc.perform(get("/api/v1/voice/whatsapp/calls/" + callSessionB.getCallId() + "/turns")
                        .header("Authorization", "Bearer " + tokenAAdmin))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Tenant Isolation: Call listing for Tenant A contains only Tenant A calls")
    void testTenantIsolation_CallListingContainsOnlyTenantCalls() throws Exception {
        mockMvc.perform(get("/api/v1/voice/whatsapp/calls")
                        .header("Authorization", "Bearer " + tokenAAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].callId", is(callSessionA.getCallId())))
                .andExpect(jsonPath("$.page.totalElements", is(1)));
    }

    @Test
    @DisplayName("Tenant Isolation: Analytics for Tenant A only aggregates Tenant A metrics, never Tenant B")
    void testTenantIsolation_AnalyticsOnlyAggregatesOwnData() throws Exception {
        mockMvc.perform(get("/api/v1/voice/whatsapp/analytics")
                        .header("Authorization", "Bearer " + tokenAAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCalls", is(1)))
                .andExpect(jsonPath("$.outboundCalls", is(1)))
                .andExpect(jsonPath("$.inboundCalls", is(0)));

        mockMvc.perform(get("/api/v1/voice/whatsapp/analytics")
                        .header("Authorization", "Bearer " + tokenBAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCalls", is(1)))
                .andExpect(jsonPath("$.inboundCalls", is(1)))
                .andExpect(jsonPath("$.outboundCalls", is(0)));
    }

    @Test
    @DisplayName("Tenant Isolation: Tenant A cannot access Tenant B's permissions")
    void testTenantIsolation_CannotAccessOtherTenantPermission() throws Exception {
        // Create permission for Tenant B
        WhatsAppCallPermission permB = WhatsAppCallPermission.builder()
                .phoneNumberId("phone_beta_01")
                .userWaId("919999999999")
                .status("GRANTED_TEMPORARY")
                .build();
        permB.setTenant(tenantB);
        permissionRepository.save(permB);

        mockMvc.perform(get("/api/v1/voice/whatsapp/permissions/919999999999")
                        .header("Authorization", "Bearer " + tokenAAdmin))
                .andExpect(status().isNotFound());
    }

    // ──────────────── 3. Role Authorization Tests ────────────────

    @Test
    @DisplayName("Role Authorization: Agent without analytics permission is forbidden from GET /analytics")
    void testRoleAuthorization_AgentWithoutAnalyticsPerm_Returns403() throws Exception {
        mockMvc.perform(get("/api/v1/voice/whatsapp/analytics")
                        .header("Authorization", "Bearer " + tokenAAgentNoPerm))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Role Authorization: Agent without WhatsApp settings permission is forbidden from POST /permissions")
    void testRoleAuthorization_AgentWithoutSettingsPerm_Returns403() throws Exception {
        RequestCallPermissionRequest req = RequestCallPermissionRequest.builder()
                .userWaId("919876543210")
                .build();

        mockMvc.perform(post("/api/v1/voice/whatsapp/permissions")
                        .header("Authorization", "Bearer " + tokenAAgentNoPerm)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    // ──────────────── 4. Validation & Error Handling Tests ────────────────

    @Test
    @DisplayName("Validation: Outbound call with invalid phone number returns 400 with structured JSON error")
    void testValidation_InvalidPhoneFormat_Returns400() throws Exception {
        InitiateWhatsAppCallRequest req = InitiateWhatsAppCallRequest.builder()
                .toWaId("123") // too short
                .build();

        mockMvc.perform(post("/api/v1/voice/whatsapp/calls")
                        .header("Authorization", "Bearer " + tokenAAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.error", is("Bad Request")));
    }

    @Test
    @DisplayName("Security: Responses must never leak internal stack traces or database info")
    void testSecurity_NoInternalStackTraceLeaked() throws Exception {
        mockMvc.perform(get("/api/v1/voice/whatsapp/calls/non_existent_call_uuid")
                        .header("Authorization", "Bearer " + tokenAAdmin))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(jsonPath("$.stackTrace").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist());
    }

    // ──────────────── 5. Call Recording Playback Security Tests ────────────────

    @Test
    @DisplayName("Recording Security: Authorized user can access their tenant's call recording metadata")
    void testRecording_AuthorizedAccess_Success() throws Exception {
        callSessionA.setRecordingPublicId("tenants/" + tenantA.getId() + "/whatsapp_recordings/" + callSessionA.getCallId());
        callSessionA.setRecordingUrl("https://res.cloudinary.com/test/rec.mp3");
        callSessionA.setRecordingStatus("AVAILABLE");
        callSessionA.setRecordingDurationSeconds(45);
        callSessionA.setRecordingFormat("mp3");
        callSessionRepository.save(callSessionA);

        org.mockito.Mockito.when(cloudinaryStorageService.isConfigured()).thenReturn(true);
        org.mockito.Mockito.when(cloudinaryStorageService.getSignedAudioPlaybackUrl(callSessionA.getRecordingPublicId()))
                .thenReturn("https://res.cloudinary.com/test/signed/rec.mp3");

        mockMvc.perform(get("/api/v1/voice/whatsapp/calls/" + callSessionA.getCallId() + "/recording")
                        .header("Authorization", "Bearer " + tokenAAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.callId", is(callSessionA.getCallId())))
                .andExpect(jsonPath("$.available", is(true)))
                .andExpect(jsonPath("$.status", is("AVAILABLE")))
                .andExpect(jsonPath("$.format", is("mp3")))
                .andExpect(jsonPath("$.playbackUrl").isNotEmpty())
                .andExpect(jsonPath("$.downloadUrl").isNotEmpty());
    }

    @Test
    @DisplayName("Recording Security: Tenant B cannot access Tenant A's call recording metadata (404)")
    void testRecording_CrossTenantAccess_Returns404() throws Exception {
        mockMvc.perform(get("/api/v1/voice/whatsapp/calls/" + callSessionA.getCallId() + "/recording")
                        .header("Authorization", "Bearer " + tokenBAdmin))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Recording Security: Unauthenticated request without JWT or token returns 401 or 403")
    void testRecording_Unauthenticated_Returns401() throws Exception {
        mockMvc.perform(get("/api/v1/voice/whatsapp/calls/" + callSessionA.getCallId() + "/recording"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Recording Streaming: Unauthenticated stream without signed token returns 401/403")
    void testRecording_StreamWithoutToken_ReturnsForbiddenOrUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/voice/whatsapp/calls/" + callSessionA.getCallId() + "/recording/stream"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("Recording Security: Expired playback token returns 403 Forbidden")
    void testRecording_ExpiredToken_Returns403() throws Exception {
        // Generate token with negative validity (-10 seconds)
        long expiredTime = System.currentTimeMillis() - 10000;
        String payload = tenantA.getId().toString() + "|" + callSessionA.getCallId() + "|" + expiredTime;
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec("default_secret".getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        String hmac = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        String expiredToken = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString((payload + "|" + hmac).getBytes(java.nio.charset.StandardCharsets.UTF_8));

        mockMvc.perform(get("/api/v1/voice/whatsapp/calls/" + callSessionA.getCallId() + "/recording/stream")
                        .param("token", expiredToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Recording Security: Tampered HMAC signature returns 403 Forbidden")
    void testRecording_TamperedHmac_Returns403() throws Exception {
        long futureTime = System.currentTimeMillis() + 60000;
        String payload = tenantA.getId().toString() + "|" + callSessionA.getCallId() + "|" + futureTime;
        String tamperedToken = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString((payload + "|invalid_tampered_hmac").getBytes(java.nio.charset.StandardCharsets.UTF_8));

        mockMvc.perform(get("/api/v1/voice/whatsapp/calls/" + callSessionA.getCallId() + "/recording/stream")
                        .param("token", tamperedToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Recording Security: Token for Call A used against Call B returns 403 Forbidden")
    void testRecording_CallMismatchToken_Returns403() throws Exception {
        long futureTime = System.currentTimeMillis() + 60000;
        String payload = tenantA.getId().toString() + "|" + callSessionA.getCallId() + "|" + futureTime;
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec("default_secret".getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        String hmac = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        String validTokenForCallA = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString((payload + "|" + hmac).getBytes(java.nio.charset.StandardCharsets.UTF_8));

        // Attempt using Call A's token on Call B
        mockMvc.perform(get("/api/v1/voice/whatsapp/calls/" + callSessionB.getCallId() + "/recording/stream")
                        .param("token", validTokenForCallA))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Recording Security: Tenant B cannot trigger retry on Tenant A's recording -> returns 404")
    void testRecording_Retry_CrossTenantAccess_Returns404() throws Exception {
        mockMvc.perform(post("/api/v1/voice/whatsapp/calls/" + callSessionA.getCallId() + "/recording/retry")
                        .header("Authorization", "Bearer " + tokenBAdmin))
                .andExpect(status().isNotFound());
    }
}
