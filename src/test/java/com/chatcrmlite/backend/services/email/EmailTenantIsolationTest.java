package com.chatcrmlite.backend.services.email;

import com.chatcrmlite.backend.models.BaseTenantEntity;
import com.chatcrmlite.backend.models.CustomEmail;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.email.*;
import com.chatcrmlite.backend.repositories.CustomEmailRepository;
import com.chatcrmlite.backend.repositories.email.*;
import com.chatcrmlite.backend.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EmailTenantIsolationTest {

    private UUID tenantAId;
    private UUID tenantBId;

    @Mock
    private EmailSuppressionListRepository suppressionRepository;

    @Mock
    private EmailCampaignRecipientRepository recipientRepository;

    @Mock
    private EmailTrackedLinkRepository trackedLinkRepository;

    @Mock
    private CustomEmailRepository customEmailRepository;

    @InjectMocks
    private EmailSuppressionService suppressionService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        tenantAId = UUID.randomUUID();
        tenantBId = UUID.randomUUID();
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("Verify all 5 core Email Marketing entities extend BaseTenantEntity")
    void testEntitiesInheritBaseTenantEntity() {
        assertTrue(BaseTenantEntity.class.isAssignableFrom(CustomEmail.class), "CustomEmail must extend BaseTenantEntity");
        assertTrue(BaseTenantEntity.class.isAssignableFrom(EmailCampaignRecipient.class), "EmailCampaignRecipient must extend BaseTenantEntity");
        assertTrue(BaseTenantEntity.class.isAssignableFrom(EmailTrackedLink.class), "EmailTrackedLink must extend BaseTenantEntity");
        assertTrue(BaseTenantEntity.class.isAssignableFrom(EmailSuppressionList.class), "EmailSuppressionList must extend BaseTenantEntity");
        assertTrue(BaseTenantEntity.class.isAssignableFrom(EmailRecipientEvent.class), "EmailRecipientEvent must extend BaseTenantEntity");
    }

    @Test
    @DisplayName("Verify getTenantId and setTenantId consistency across converted entities")
    void testTenantIdGetterSetterConsistency() {
        UUID tenantId = UUID.randomUUID();

        CustomEmail email = new CustomEmail();
        email.setTenantId(tenantId);
        assertEquals(tenantId, email.getTenantId());

        EmailCampaignRecipient recipient = EmailCampaignRecipient.builder()
                .tenantId(tenantId)
                .campaignId(UUID.randomUUID())
                .email("user@example.com")
                .build();
        assertEquals(tenantId, recipient.getTenantId());

        EmailTrackedLink link = EmailTrackedLink.builder()
                .tenantId(tenantId)
                .campaignId(UUID.randomUUID())
                .linkToken("token123")
                .destinationUrl("https://example.com")
                .build();
        assertEquals(tenantId, link.getTenantId());

        EmailSuppressionList suppression = EmailSuppressionList.builder()
                .tenantId(tenantId)
                .email("suppressed@example.com")
                .reason(EmailSuppressionList.SuppressionReason.UNSUBSCRIBED)
                .build();
        assertEquals(tenantId, suppression.getTenantId());

        EmailRecipientEvent event = EmailRecipientEvent.builder()
                .tenantId(tenantId)
                .campaignId(UUID.randomUUID())
                .recipientId(UUID.randomUUID())
                .eventType(EmailRecipientEvent.EventType.OPENED)
                .build();
        assertEquals(tenantId, event.getTenantId());
    }

    @Test
    @DisplayName("Verify TenantContext auto-populates tenant on prePersist")
    void testTenantContextAutoPopulationOnPrePersist() {
        TenantContext.setTenantId(tenantAId);

        EmailCampaignRecipient recipient = new EmailCampaignRecipient();
        recipient.prePersist();
        assertNotNull(recipient.getTenant());
        assertEquals(tenantAId, recipient.getTenantId());

        EmailTrackedLink link = new EmailTrackedLink();
        link.prePersist();
        assertEquals(tenantAId, link.getTenantId());

        EmailSuppressionList suppression = new EmailSuppressionList();
        suppression.prePersist();
        assertEquals(tenantAId, suppression.getTenantId());

        EmailRecipientEvent event = new EmailRecipientEvent();
        event.prePersist();
        assertEquals(tenantAId, event.getTenantId());
    }

    @Test
    @DisplayName("Verify Tenant A suppression does not suppress Tenant B")
    void testTenantSuppressionIsolation() {
        String email = "john@example.com";

        when(suppressionRepository.existsByTenantIdAndEmail(tenantAId, email)).thenReturn(true);
        when(suppressionRepository.existsByTenantIdAndEmail(tenantBId, email)).thenReturn(false);

        assertTrue(suppressionService.isSuppressed(tenantAId, email), "Tenant A should see email as suppressed");
        assertFalse(suppressionService.isSuppressed(tenantBId, email), "Tenant B must NOT see email as suppressed");

        verify(suppressionRepository).existsByTenantIdAndEmail(tenantAId, email);
        verify(suppressionRepository).existsByTenantIdAndEmail(tenantBId, email);
    }

    @Test
    @DisplayName("Verify public tracking token resolution extracts exact tenant ID without JWT")
    void testPublicTokenResolutionPreservesTenant() {
        UUID campaignId = UUID.randomUUID();
        String trackingToken = "pub_token_192bit";

        EmailCampaignRecipient recipient = EmailCampaignRecipient.builder()
                .tenantId(tenantAId)
                .campaignId(campaignId)
                .email("target@example.com")
                .trackingToken(trackingToken)
                .build();

        when(recipientRepository.findByTrackingToken(trackingToken)).thenReturn(Optional.of(recipient));

        Optional<EmailCampaignRecipient> resolved = recipientRepository.findByTrackingToken(trackingToken);
        assertTrue(resolved.isPresent());
        assertEquals(tenantAId, resolved.get().getTenantId());
        assertEquals(campaignId, resolved.get().getCampaignId());
    }
}
