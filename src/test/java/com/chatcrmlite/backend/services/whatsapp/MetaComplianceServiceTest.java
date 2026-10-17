package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.models.MetaDataDeletionRequest;
import com.chatcrmlite.backend.models.SecurityNotificationOutbox;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.repositories.MetaDataDeletionRequestRepository;
import com.chatcrmlite.backend.repositories.SecurityNotificationOutboxRepository;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MetaComplianceServiceTest {

    @Mock
    private WhatsAppConfigRepository whatsappConfigRepository;

    @Mock
    private MetaDataDeletionRequestRepository deletionRequestRepository;

    @Mock
    private SecurityNotificationOutboxRepository securityNotificationOutboxRepository;

    @InjectMocks
    private MetaComplianceService complianceService;

    private Tenant tenantA;
    private Tenant tenantB;
    private WhatsAppConfig configA;
    private WhatsAppConfig configB;

    @BeforeEach
    void setUp() {
        tenantA = new Tenant();
        tenantA.setId(UUID.randomUUID());
        tenantA.setBusinessName("Tenant A");

        tenantB = new Tenant();
        tenantB.setId(UUID.randomUUID());
        tenantB.setBusinessName("Tenant B");

        configA = new WhatsAppConfig();
        configA.setId(UUID.randomUUID());
        configA.setTenant(tenantA);
        configA.setPhoneNumberId("111111111");
        configA.setMetaUserId("meta_user_aaa");
        configA.setAccessToken("token_aaa_secret");
        configA.setConnectionStatus("ACTIVE");

        configB = new WhatsAppConfig();
        configB.setId(UUID.randomUUID());
        configB.setTenant(tenantB);
        configB.setPhoneNumberId("222222222");
        configB.setMetaUserId("meta_user_bbb");
        configB.setAccessToken("token_bbb_secret");
        configB.setConnectionStatus("ACTIVE");
    }

    @Test
    @DisplayName("Deauthorization should revoke credentials and disconnect only the matching tenant config")
    void testDeauthorizationSuccess() {
        when(whatsappConfigRepository.findAllByMetaUserId("meta_user_aaa")).thenReturn(List.of(configA));

        Map<String, Object> result = complianceService.handleDeauthorization("meta_user_aaa", 1700000000L);

        assertTrue((Boolean) result.get("success"));
        assertEquals(1, result.get("configsAffected"));

        // Verify configA is disconnected and token is cleared
        assertEquals("DISCONNECTED", configA.getConnectionStatus());
        assertEquals("DEAUTHORIZED", configA.getAccountStatus());
        assertNull(configA.getAccessToken());
        verify(whatsappConfigRepository, times(1)).save(configA);

        // Verify audit outbox is written
        verify(securityNotificationOutboxRepository, times(1)).save(any(SecurityNotificationOutbox.class));

        // Verify unrelated tenantB was NEVER modified or saved
        assertEquals("ACTIVE", configB.getConnectionStatus());
        assertNotNull(configB.getAccessToken());
        verify(whatsappConfigRepository, never()).save(configB);
    }

    @Test
    @DisplayName("Deauthorization on unmapped Meta identity should handle gracefully and idempotently")
    void testDeauthorizationUnmappedIdentity() {
        when(whatsappConfigRepository.findAllByMetaUserId("meta_unknown")).thenReturn(List.of());
        when(whatsappConfigRepository.findAllByBusinessId("meta_unknown")).thenReturn(List.of());

        Map<String, Object> result = complianceService.handleDeauthorization("meta_unknown", 1700000000L);

        assertTrue((Boolean) result.get("success"));
        assertEquals(0, result.get("configsAffected"));
        verify(whatsappConfigRepository, never()).save(any());
        verify(securityNotificationOutboxRepository, never()).save(any());
    }

    @Test
    @DisplayName("Data deletion should purge credentials, anonymize audit outbox, and return valid contract")
    void testDataDeletionSuccess() {
        when(deletionRequestRepository.findFirstByMetaUserIdAndStatus("meta_user_aaa", "COMPLETED"))
                .thenReturn(Optional.empty());
        when(whatsappConfigRepository.findAllByMetaUserId("meta_user_aaa")).thenReturn(List.of(configA));

        SecurityNotificationOutbox auditEntry = SecurityNotificationOutbox.builder()
                .metaUserId("meta_user_aaa")
                .eventType("META_APP_DEAUTHORIZED")
                .sourceEventId("test_source_id")
                .description("Deauth event")
                .build();
        when(securityNotificationOutboxRepository.findAllByMetaUserId("meta_user_aaa")).thenReturn(List.of(auditEntry));

        Map<String, String> response = complianceService.handleDataDeletion("meta_user_aaa", "https://api.gyanvaniai.online");

        assertNotNull(response);
        String confirmationCode = response.get("confirmation_code");
        String url = response.get("url");

        assertNotNull(confirmationCode);
        assertFalse(confirmationCode.isBlank());
        assertTrue(url.contains(confirmationCode));
        assertTrue(url.startsWith("https://api.gyanvaniai.online/api/v1/integrations/meta/data-deletion/status?code="));

        // Verify configA credentials were wiped
        assertNull(configA.getAccessToken());
        assertNull(configA.getVerifyToken());
        assertEquals("DISCONNECTED", configA.getConnectionStatus());
        assertEquals("DELETED", configA.getAccountStatus());
        assertNull(configA.getMetaUserId());
        verify(whatsappConfigRepository, times(1)).save(configA);

        // Verify SecurityNotificationOutbox was anonymized for GDPR compliance
        assertEquals("ANONYMIZED_GDPR", auditEntry.getMetaUserId());
        verify(securityNotificationOutboxRepository, times(1)).save(auditEntry);

        // Verify deletion request was saved as COMPLETED
        ArgumentCaptor<MetaDataDeletionRequest> requestCaptor = ArgumentCaptor.forClass(MetaDataDeletionRequest.class);
        verify(deletionRequestRepository, atLeastOnce()).save(requestCaptor.capture());
        MetaDataDeletionRequest lastSaved = requestCaptor.getValue();
        assertEquals("COMPLETED", lastSaved.getStatus());
        assertNotNull(lastSaved.getCompletedAt());
        assertEquals(confirmationCode, lastSaved.getConfirmationCode());

        // Verify unrelated tenantB was untouched
        assertEquals("ACTIVE", configB.getConnectionStatus());
        assertNotNull(configB.getAccessToken());
        verify(whatsappConfigRepository, never()).save(configB);
    }

    @Test
    @DisplayName("Data deletion should be idempotent and return existing confirmation code if already purged")
    void testDataDeletionIdempotency() {
        // Config already purged
        configA.setAccessToken(null);
        configA.setConnectionStatus("DISCONNECTED");
        when(whatsappConfigRepository.findAllByMetaUserId("meta_user_aaa")).thenReturn(List.of(configA));

        MetaDataDeletionRequest existingRequest = MetaDataDeletionRequest.builder()
                .confirmationCode("existing_code_123456789")
                .metaUserId("meta_user_aaa")
                .status("COMPLETED")
                .requestedAt(Instant.now().minusSeconds(3600))
                .completedAt(Instant.now().minusSeconds(3500))
                .build();

        when(deletionRequestRepository.findFirstByMetaUserIdAndStatus("meta_user_aaa", "COMPLETED"))
                .thenReturn(Optional.of(existingRequest));

        Map<String, String> response = complianceService.handleDataDeletion("meta_user_aaa", "https://api.gyanvaniai.online");

        assertEquals("existing_code_123456789", response.get("confirmation_code"));
        assertTrue(response.get("url").contains("existing_code_123456789"));
        verify(whatsappConfigRepository, never()).save(any());
    }

    @Test
    @DisplayName("Data deletion for re-onboarded user with active credentials forces fresh deletion")
    void testDataDeletionReOnboardedForcesFreshDeletion() {
        // Config has active credentials again
        configA.setAccessToken("new_active_token");
        configA.setConnectionStatus("ACTIVE");
        when(whatsappConfigRepository.findAllByMetaUserId("meta_user_aaa")).thenReturn(List.of(configA));

        MetaDataDeletionRequest oldRequest = MetaDataDeletionRequest.builder()
                .confirmationCode("old_code_999")
                .metaUserId("meta_user_aaa")
                .status("COMPLETED")
                .build();

        when(deletionRequestRepository.findFirstByMetaUserIdAndStatus("meta_user_aaa", "COMPLETED"))
                .thenReturn(Optional.of(oldRequest));

        Map<String, String> response = complianceService.handleDataDeletion("meta_user_aaa", "https://api.gyanvaniai.online");

        // Must generate a new confirmation code, not return old_code_999
        assertNotEquals("old_code_999", response.get("confirmation_code"));
        assertNull(configA.getAccessToken());
        assertEquals("DISCONNECTED", configA.getConnectionStatus());
        verify(whatsappConfigRepository, times(1)).save(configA);
    }

    @Test
    @DisplayName("Status query returns request by confirmation code")
    void testGetDeletionStatus() {
        MetaDataDeletionRequest request = MetaDataDeletionRequest.builder()
                .confirmationCode("test_code_xyz")
                .status("COMPLETED")
                .build();

        when(deletionRequestRepository.findByConfirmationCode("test_code_xyz")).thenReturn(Optional.of(request));
        when(deletionRequestRepository.findByConfirmationCode("unknown_code")).thenReturn(Optional.empty());

        Optional<MetaDataDeletionRequest> found = complianceService.getDeletionStatus("test_code_xyz");
        assertTrue(found.isPresent());
        assertEquals("COMPLETED", found.get().getStatus());

        Optional<MetaDataDeletionRequest> notFound = complianceService.getDeletionStatus("unknown_code");
        assertTrue(notFound.isEmpty());
    }
}
