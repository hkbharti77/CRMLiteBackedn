package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.WhatsAppCampaign;
import com.chatcrmlite.backend.models.WhatsAppCampaignRecipient;
import com.chatcrmlite.backend.models.WhatsAppCampaignTrackedLink;
import com.chatcrmlite.backend.repositories.WhatsAppCampaignRecipientRepository;
import com.chatcrmlite.backend.repositories.WhatsAppCampaignTrackedLinkRepository;
import com.chatcrmlite.backend.services.whatsapp.campaign.CampaignAnalyticsService;
import com.chatcrmlite.backend.services.whatsapp.campaign.WhatsAppLinkTrackingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WhatsAppLinkTrackingServiceTest {

    @Mock
    private WhatsAppCampaignTrackedLinkRepository trackedLinkRepository;

    @Mock
    private WhatsAppCampaignRecipientRepository recipientRepository;

    @Mock
    private CampaignAnalyticsService analyticsService;

    private ObjectMapper objectMapper;

    private WhatsAppLinkTrackingService trackingService;

    private Tenant tenant;
    private WhatsAppCampaign campaign;
    private WhatsAppCampaignRecipient recipient;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        objectMapper = new ObjectMapper();
        trackingService = new WhatsAppLinkTrackingService(
                trackedLinkRepository,
                recipientRepository,
                analyticsService,
                objectMapper
        );
        ReflectionTestUtils.setField(trackingService, "baseUrl", "https://crm.gyanvani.ai");

        tenant = new Tenant();
        tenant.setId(UUID.randomUUID());

        campaign = WhatsAppCampaign.builder()
                .id(UUID.randomUUID())
                .name("Diwali Festival Sale")
                .build();
        campaign.setTenant(tenant);

        recipient = WhatsAppCampaignRecipient.builder()
                .id(UUID.randomUUID())
                .campaign(campaign)
                .phoneNumber("+919876543210")
                .build();
        recipient.setTenant(tenant);
    }

    @Test
    @DisplayName("generateToken should produce 12-char unique tokens with zero collisions")
    void generateToken_shouldProduceUniqueTokens() {
        Set<String> tokens = new HashSet<>();
        int count = 500;
        for (int i = 0; i < count; i++) {
            String token = trackingService.generateToken();
            assertNotNull(token);
            assertEquals(12, token.length());
            tokens.add(token);
        }
        assertEquals(count, tokens.size(), "All generated tokens must be distinct");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com/shop",
            "http://store.mybusiness.in/product/123",
            "https://gyanvani.ai/pricing?ref=wa",
            "https://sub.domain.co.uk/page#offer"
    })
    @DisplayName("isValidDestinationUrl should accept safe public HTTP/HTTPS URLs")
    void isValidDestinationUrl_shouldAcceptSafeUrls(String safeUrl) {
        assertTrue(trackingService.isValidDestinationUrl(safeUrl));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1/admin",
            "http://localhost:8080/secret",
            "http://0.0.0.0/debug",
            "http://10.0.0.1/internal",
            "http://172.16.0.1/keys",
            "http://172.31.255.255/admin",
            "http://192.168.1.1/router",
            "http://169.254.169.254/latest/meta-data", // Cloud metadata SSRF
            "ftp://files.example.com/malware.exe",
            "javascript:alert(1)",
            "mailto:admin@company.com",
            "",
            "   "
    })
    @DisplayName("isValidDestinationUrl should strictly reject SSRF, private IPs, loopback and non-HTTP protocols")
    void isValidDestinationUrl_shouldRejectMaliciousUrls(String maliciousUrl) {
        assertFalse(trackingService.isValidDestinationUrl(maliciousUrl));
    }

    @Test
    @DisplayName("wrapUrl should persist WhatsAppCampaignTrackedLink and return tracking URL")
    void wrapUrl_shouldPersistAndReturnTrackingUrl() {
        when(trackedLinkRepository.findByToken(any())).thenReturn(Optional.empty());

        String originalUrl = "https://example.com/diwali-offer";
        String trackingUrl = trackingService.wrapUrl(campaign, recipient, originalUrl, 0, "Claim 50% Off");

        assertNotNull(trackingUrl);
        assertTrue(trackingUrl.startsWith("https://crm.gyanvani.ai/api/v1/t/w/"));

        ArgumentCaptor<WhatsAppCampaignTrackedLink> captor = ArgumentCaptor.forClass(WhatsAppCampaignTrackedLink.class);
        verify(trackedLinkRepository, times(1)).save(captor.capture());

        WhatsAppCampaignTrackedLink saved = captor.getValue();
        assertEquals(originalUrl, saved.getOriginalUrl());
        assertEquals(0, saved.getButtonIndex());
        assertEquals("Claim 50% Off", saved.getButtonText());
        assertEquals(campaign, saved.getCampaign());
        assertEquals(recipient, saved.getRecipient());
        assertEquals(0, saved.getClickCount());
    }

    @Test
    @DisplayName("wrapBodyUrls should detect and replace raw URLs in body text")
    void wrapBodyUrls_shouldReplaceUrlsInText() {
        when(trackedLinkRepository.findByToken(any())).thenReturn(Optional.empty());

        String body = "Hello! Check our festive catalog at https://example.com/catalog and book at https://example.com/book now!";
        String wrappedBody = trackingService.wrapBodyUrls(campaign, recipient, body);

        assertNotNull(wrappedBody);
        assertFalse(wrappedBody.contains("https://example.com/catalog"));
        assertFalse(wrappedBody.contains("https://example.com/book"));
        assertTrue(wrappedBody.contains("https://crm.gyanvani.ai/api/v1/t/w/"));
        verify(trackedLinkRepository, times(2)).save(any());
    }

    @Test
    @DisplayName("wrapButtonsJson should inspect and wrap URL CTA buttons in JSON")
    void wrapButtonsJson_shouldWrapUrlButtons() {
        when(trackedLinkRepository.findByToken(any())).thenReturn(Optional.empty());

        String buttonsJson = "[{\"type\":\"URL\",\"text\":\"Visit Store\",\"url\":\"https://example.com/store\"},{\"type\":\"QUICK_REPLY\",\"text\":\"Stop\"}]";
        String wrappedJson = trackingService.wrapButtonsJson(campaign, recipient, buttonsJson);

        assertNotNull(wrappedJson);
        assertTrue(wrappedJson.contains("https://crm.gyanvani.ai/api/v1/t/w/"));
        assertTrue(wrappedJson.contains("Visit Store"));
        assertTrue(wrappedJson.contains("QUICK_REPLY"));
        assertFalse(wrappedJson.contains("https://example.com/store"));
        verify(trackedLinkRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("resolveAndRecordClick should record atomic click counts on link & recipient and rollup analytics")
    void resolveAndRecordClick_shouldRecordClickAndReturnOriginalUrl() {
        String token = "abc123xyz890";
        String destinationUrl = "https://example.com/landing";
        UUID linkId = UUID.randomUUID();

        WhatsAppCampaignTrackedLink link = WhatsAppCampaignTrackedLink.builder()
                .id(linkId)
                .token(token)
                .originalUrl(destinationUrl)
                .campaign(campaign)
                .recipient(recipient)
                .clickCount(0)
                .build();

        when(trackedLinkRepository.findByToken(token)).thenReturn(Optional.of(link));

        Optional<String> result = trackingService.resolveAndRecordClick(token, "203.0.113.195");

        assertTrue(result.isPresent());
        assertEquals(destinationUrl, result.get());

        verify(trackedLinkRepository, times(1)).recordClick(eq(linkId), any(LocalDateTime.class));
        verify(recipientRepository, times(1)).recordClick(eq(recipient.getId()), any(LocalDateTime.class));
        verify(analyticsService, times(1)).updateAnalyticsRollup(campaign);
    }

    @Test
    @DisplayName("resolveAndRecordClick should return empty Optional for unknown token")
    void resolveAndRecordClick_shouldReturnEmptyForUnknownToken() {
        when(trackedLinkRepository.findByToken("unknownToken")).thenReturn(Optional.empty());

        Optional<String> result = trackingService.resolveAndRecordClick("unknownToken", "203.0.113.195");

        assertTrue(result.isEmpty());
        verify(trackedLinkRepository, never()).recordClick(any(), any());
        verify(recipientRepository, never()).recordClick(any(), any());
        verify(analyticsService, never()).updateAnalyticsRollup(any());
    }
}
