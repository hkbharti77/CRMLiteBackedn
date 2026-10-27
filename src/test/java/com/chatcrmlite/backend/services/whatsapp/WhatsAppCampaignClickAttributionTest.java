package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.clients.WhatsAppClient;
import com.chatcrmlite.backend.models.*;
import com.chatcrmlite.backend.repositories.*;
import com.chatcrmlite.backend.services.whatsapp.campaign.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class WhatsAppCampaignClickAttributionTest {

    @Mock
    private CampaignQueueProducer queueProducer;

    @Mock
    private WhatsAppCampaignRepository campaignRepository;

    @Mock
    private WhatsAppCampaignRecipientRepository recipientRepository;

    @Mock
    private WhatsAppConfigRepository whatsAppConfigRepository;

    @Mock
    private WhatsAppClient whatsappClient;

    @Mock
    private CampaignAnalyticsService analyticsService;

    @Mock
    private CampaignAuditService auditService;

    @Mock
    private WhatsAppRecipientResolver recipientResolver;

    @Mock
    private ContactRepository contactRepository;

    @Mock
    private WhatsAppCampaignTrackedLinkRepository trackedLinkRepository;

    @Mock
    private com.chatcrmlite.backend.repositories.WhatsAppCampaignVariantRepository variantRepository;

    private ObjectMapper objectMapper;

    private WhatsAppLinkTrackingService linkTrackingService;

    private CampaignMessageWorker campaignWorker;

    private Tenant tenant;
    private User owner;
    private WhatsAppCampaign campaign;
    private WhatsAppTemplateSnapshot snapshot;
    private WhatsAppCampaignRecipient recipient;
    private WhatsAppConfig config;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        objectMapper = new ObjectMapper();

        linkTrackingService = new WhatsAppLinkTrackingService(
                trackedLinkRepository,
                recipientRepository,
                analyticsService,
                objectMapper,
                variantRepository
        );
        ReflectionTestUtils.setField(linkTrackingService, "baseUrl", "https://wa.gyanvani.ai");

        campaignWorker = new CampaignMessageWorker(
                queueProducer,
                campaignRepository,
                recipientRepository,
                whatsAppConfigRepository,
                whatsappClient,
                analyticsService,
                auditService,
                objectMapper,
                recipientResolver,
                contactRepository,
                linkTrackingService,
                variantRepository
        );

        tenant = new Tenant();
        tenant.setId(UUID.randomUUID());

        owner = new User();
        owner.setId(UUID.randomUUID());
        owner.setTenant(tenant);

        snapshot = WhatsAppTemplateSnapshot.builder()
                .id(UUID.randomUUID())
                .name("festive_promo")
                .language("en")
                .category("MARKETING")
                .status("APPROVED")
                .bodyText("Celebrate with us! Visit https://example.com/diwali to claim your gift!")
                .buttonsJson("[{\"type\":\"URL\",\"text\":\"Claim Now\",\"url\":\"https://example.com/claim\"}]")
                .build();

        campaign = WhatsAppCampaign.builder()
                .id(UUID.randomUUID())
                .name("Diwali Mega Broadcast")
                .owner(owner)
                .templateSnapshot(snapshot)
                .status(WhatsAppCampaign.Status.RUNNING)
                .priority(WhatsAppCampaign.Priority.HIGH)
                .build();
        campaign.setTenant(tenant);

        recipient = WhatsAppCampaignRecipient.builder()
                .id(UUID.randomUUID())
                .campaign(campaign)
                .phoneNumber("+919988776655")
                .status(WhatsAppCampaignRecipient.RecipientStatus.QUEUED)
                .build();
        recipient.setTenant(tenant);

        config = new WhatsAppConfig();
        config.setAccessToken("test-access-token");
        config.setPhoneNumberId("test-phone-number-id");
        config.setMessagingTier("TIER_10K");
    }

    @Test
    @DisplayName("End-to-End Campaign Link Wrapping: Worker wraps both body link and CTA button with tracking tokens")
    void testCampaignDispatch_wrapsBothBodyAndCtaLinks() {
        when(campaignRepository.findById(campaign.getId())).thenReturn(Optional.of(campaign));
        when(whatsAppConfigRepository.findByUserId(owner.getId())).thenReturn(Optional.of(config));
        when(queueProducer.popTask(campaign.getId().toString()))
                .thenReturn(recipient.getId().toString())
                .thenReturn(null); // Stop after 1 task

        when(recipientRepository.findById(recipient.getId())).thenReturn(Optional.of(recipient));
        when(recipientRepository.countByCampaignAndStatusIn(any(), any())).thenReturn(0L);

        WhatsAppRecipientResolver.ResolvedRecipient resolved = new WhatsAppRecipientResolver.ResolvedRecipient(
                "+919988776655", null, null, WhatsAppRecipientResolver.RecipientIdentityType.PHONE
        );
        when(recipientResolver.resolve(any(), eq("+919988776655"), anyBoolean())).thenReturn(resolved);

        when(whatsappClient.sendMessageToRecipient(any(), anyString(), anyString(), anyString()))
                .thenReturn("wamid.HBgLMTIzNDU2");

        // Execute batch dispatch
        campaignWorker.processCampaignBatch(campaign.getId());

        // Verify tracked links were saved (one for body, one for button)
        verify(trackedLinkRepository, atLeast(2)).save(any(WhatsAppCampaignTrackedLink.class));

        // Verify outbound message content sent to WhatsApp has tracking URLs and no raw URLs
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(whatsappClient, times(1)).sendMessageToRecipient(
                eq(resolved),
                textCaptor.capture(),
                eq(config.getAccessToken()),
                eq(config.getPhoneNumberId())
        );

        String sentText = textCaptor.getValue();
        assertNotNull(sentText);
        assertFalse(sentText.contains("https://example.com/diwali"), "Original body link must be replaced with tracking URL");
        assertFalse(sentText.contains("https://example.com/claim"), "Original button link must be replaced with tracking URL");
        assertTrue(sentText.contains("https://wa.gyanvani.ai/api/v1/t/w/"), "Message must contain tracking URL");
        assertTrue(sentText.contains("Claim Now"), "Button label must be rendered in message");

        // Verify recipient status updated to SENT
        assertEquals(WhatsAppCampaignRecipient.RecipientStatus.SENT, recipient.getStatus());
        assertEquals("wamid.HBgLMTIzNDU2", recipient.getWaMessageId());
        assertNotNull(recipient.getSentAt());
    }

    @Test
    @DisplayName("End-to-End Click Attribution: Clicking tracking link updates link and recipient counters and rollups analytics")
    void testClickAttribution_updatesCountersAndRollup() {
        String token = "tok998877665";
        String originalUrl = "https://example.com/diwali";
        UUID linkId = UUID.randomUUID();

        WhatsAppCampaignTrackedLink link = WhatsAppCampaignTrackedLink.builder()
                .id(linkId)
                .token(token)
                .originalUrl(originalUrl)
                .campaign(campaign)
                .recipient(recipient)
                .clickCount(0)
                .build();

        when(trackedLinkRepository.findByToken(token)).thenReturn(Optional.of(link));

        // Customer clicks link
        Optional<String> destination = linkTrackingService.resolveAndRecordClick(token, "49.205.100.12");

        assertTrue(destination.isPresent());
        assertEquals(originalUrl, destination.get());

        // Verify atomic recordClick called on tracked link
        verify(trackedLinkRepository, times(1)).recordClick(eq(linkId), any(LocalDateTime.class));

        // Verify atomic recordClick called on recipient
        verify(recipientRepository, times(1)).recordClick(eq(recipient.getId()), any(LocalDateTime.class));

        // Verify analytics rollup triggered for campaign
        verify(analyticsService, times(1)).updateAnalyticsRollup(campaign);
    }
}
