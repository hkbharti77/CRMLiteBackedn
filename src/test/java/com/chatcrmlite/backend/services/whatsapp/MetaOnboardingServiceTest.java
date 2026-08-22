package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MetaOnboardingServiceTest {

    @InjectMocks
    private MetaOnboardingService metaOnboardingService;

    @Mock
    private RestTemplate restTemplate;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private WhatsAppConfigRepository whatsappConfigRepository;

    private User testUser;
    private Tenant testTenant;

    @BeforeEach
    void setUp() {
        testTenant = new Tenant();
        testTenant.setId(UUID.randomUUID());
        testTenant.setBusinessName("Test Business");

        testUser = new User();
        testUser.setId(UUID.randomUUID());
        testUser.setEmail("owner@business.com");
        testUser.setTenant(testTenant);

        ReflectionTestUtils.setField(metaOnboardingService, "appId", "1573307991099476");
        ReflectionTestUtils.setField(metaOnboardingService, "appSecret", "mock_secret_12345");
        ReflectionTestUtils.setField(metaOnboardingService, "configId", "1052344107323702");
        ReflectionTestUtils.setField(metaOnboardingService, "graphApiVersion", "v21.0");
        ReflectionTestUtils.setField(metaOnboardingService, "apiBaseUrl", "https://graph.facebook.com");
    }

    @Test
    @DisplayName("Should create an onboarding session with valid TTL and single-use sessionId")
    void testCreateSession() {
        Map<String, Object> session = metaOnboardingService.createSession(testUser);

        assertNotNull(session);
        assertNotNull(session.get("sessionId"));
        assertEquals("1573307991099476", session.get("appId"));
        assertEquals("1052344107323702", session.get("configId"));
        assertNotNull(session.get("launcherUrl"));
    }

    @Test
    @DisplayName("Should reject session validation on replay (single-use protection)")
    void testSessionReplayRejection() {
        Map<String, Object> session = metaOnboardingService.createSession(testUser);
        String sessionId = (String) session.get("sessionId");

        // First consume succeeds
        MetaOnboardingService.OnboardingSession consumed = metaOnboardingService.validateAndConsumeSession(sessionId, testUser);
        assertNotNull(consumed);
        assertEquals("EXCHANGED", consumed.getStatus());

        // Replay attempt must fail with IllegalStateException
        assertThrows(IllegalStateException.class, () -> {
            metaOnboardingService.validateAndConsumeSession(sessionId, testUser);
        });
    }

    @Test
    @DisplayName("Should reject session validation if tenant mismatches")
    void testTenantMismatchRejection() {
        Map<String, Object> session = metaOnboardingService.createSession(testUser);
        String sessionId = (String) session.get("sessionId");

        User maliciousUser = new User();
        maliciousUser.setId(UUID.randomUUID());
        maliciousUser.setEmail("hacker@other.com");
        Tenant otherTenant = new Tenant();
        otherTenant.setId(UUID.randomUUID());
        maliciousUser.setTenant(otherTenant);

        assertThrows(SecurityException.class, () -> {
            metaOnboardingService.validateAndConsumeSession(sessionId, maliciousUser);
        });
    }

    @Test
    @DisplayName("Should successfully exchange code, inspect token, resolve WABA & phone, and persist config")
    void testExchangeAndProvisionSuccess() {
        Map<String, Object> session = metaOnboardingService.createSession(testUser);
        String sessionId = (String) session.get("sessionId");

        // 1. Mock Token Exchange
        String tokenExchangeJson = "{\"access_token\":\"EAAG_mock_token_123\",\"token_type\":\"bearer\",\"expires_in\":5184000}";
        when(restTemplate.getForEntity(contains("/oauth/access_token"), eq(String.class)))
                .thenReturn(new ResponseEntity<>(tokenExchangeJson, HttpStatus.OK));

        // 2. Mock /debug_token
        String debugTokenJson = "{\"data\":{\"is_valid\":true,\"granular_scopes\":[{\"scope\":\"whatsapp_business_management\",\"target_ids\":[\"biz_123\"]},{\"scope\":\"whatsapp_business_messaging\",\"target_ids\":[\"waba_456\"]}]}}";
        when(restTemplate.getForEntity(contains("/debug_token"), eq(String.class)))
                .thenReturn(new ResponseEntity<>(debugTokenJson, HttpStatus.OK));

        // 3. Mock owned WABA lookup
        String wabaAccountsJson = "{\"data\":[{\"id\":\"waba_456\",\"name\":\"Test WABA\"}]}";
        when(restTemplate.getForEntity(contains("/owned_whatsapp_business_accounts"), eq(String.class)))
                .thenReturn(new ResponseEntity<>(wabaAccountsJson, HttpStatus.OK));

        // 4. Mock phone numbers lookup
        String phoneNumbersJson = "{\"data\":[{\"id\":\"phone_789\",\"display_phone_number\":\"+1 555-0199\",\"verified_name\":\"GyanVaniAi\",\"quality_rating\":\"GREEN\"}]}";
        when(restTemplate.getForEntity(contains("/phone_numbers"), eq(String.class)))
                .thenReturn(new ResponseEntity<>(phoneNumbersJson, HttpStatus.OK));

        // 5. Mock subscribed_apps webhook registration
        String subscribedAppsJson = "{\"success\":true}";
        when(restTemplate.postForEntity(contains("/subscribed_apps"), isNull(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(subscribedAppsJson, HttpStatus.OK));

        // 6. Mock DB repo save
        when(whatsappConfigRepository.findByTenantId(testTenant.getId())).thenReturn(Optional.empty());
        when(whatsappConfigRepository.save(any(WhatsAppConfig.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> result = metaOnboardingService.exchangeAndProvision("oauth_code_xyz", sessionId, testUser);

        assertNotNull(result);
        assertTrue((Boolean) result.get("success"));
        assertEquals("phone_789", result.get("phoneNumberId"));
        assertEquals("+1 555-0199", result.get("displayPhoneNumber"));
        assertEquals("waba_456", result.get("wabaId"));
        assertEquals("ACTIVE", result.get("connectionStatus"));
        assertEquals("ACTIVE", result.get("webhookSubscriptionStatus"));

        verify(whatsappConfigRepository, times(1)).save(any(WhatsAppConfig.class));
    }

    @Test
    @DisplayName("Should mark webhook as FAILED while keeping connection ACTIVE if webhook subscription fails")
    void testProvisioningWithWebhookFailure() {
        Map<String, Object> session = metaOnboardingService.createSession(testUser);
        String sessionId = (String) session.get("sessionId");

        String tokenExchangeJson = "{\"access_token\":\"EAAG_mock_token_123\",\"token_type\":\"bearer\",\"expires_in\":5184000}";
        when(restTemplate.getForEntity(contains("/oauth/access_token"), eq(String.class)))
                .thenReturn(new ResponseEntity<>(tokenExchangeJson, HttpStatus.OK));

        String debugTokenJson = "{\"data\":{\"is_valid\":true,\"granular_scopes\":[{\"scope\":\"whatsapp_business_management\",\"target_ids\":[\"biz_123\"]},{\"scope\":\"whatsapp_business_messaging\",\"target_ids\":[\"waba_456\"]}]}}";
        when(restTemplate.getForEntity(contains("/debug_token"), eq(String.class)))
                .thenReturn(new ResponseEntity<>(debugTokenJson, HttpStatus.OK));

        String wabaAccountsJson = "{\"data\":[{\"id\":\"waba_456\",\"name\":\"Test WABA\"}]}";
        when(restTemplate.getForEntity(contains("/owned_whatsapp_business_accounts"), eq(String.class)))
                .thenReturn(new ResponseEntity<>(wabaAccountsJson, HttpStatus.OK));

        String phoneNumbersJson = "{\"data\":[{\"id\":\"phone_789\",\"display_phone_number\":\"+1 555-0199\",\"verified_name\":\"GyanVaniAi\",\"quality_rating\":\"GREEN\"}]}";
        when(restTemplate.getForEntity(contains("/phone_numbers"), eq(String.class)))
                .thenReturn(new ResponseEntity<>(phoneNumbersJson, HttpStatus.OK));

        // Subscribed apps fails
        when(restTemplate.postForEntity(contains("/subscribed_apps"), isNull(), eq(String.class)))
                .thenThrow(new RuntimeException("Meta Webhook API timeout"));

        when(whatsappConfigRepository.findByTenantId(testTenant.getId())).thenReturn(Optional.empty());
        when(whatsappConfigRepository.save(any(WhatsAppConfig.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> result = metaOnboardingService.exchangeAndProvision("oauth_code_xyz", sessionId, testUser);

        assertNotNull(result);
        assertTrue((Boolean) result.get("success"));
        assertEquals("ACTIVE", result.get("connectionStatus"));
        assertEquals("FAILED", result.get("webhookSubscriptionStatus"));
        assertNotNull(result.get("webhookSubscriptionError"));
    }
}
