package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.repositories.email.EmailCampaignRecipientRepository;
import com.chatcrmlite.backend.repositories.email.EmailTrackedLinkRepository;
import com.chatcrmlite.backend.services.email.EmailTrackingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class EmailTrackingServiceTest {

    @Mock
    private EmailTrackedLinkRepository trackedLinkRepository;

    @Mock
    private EmailCampaignRecipientRepository recipientRepository;

    @InjectMocks
    private EmailTrackingService trackingService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        ReflectionTestUtils.setField(trackingService, "baseUrl", "http://localhost:3000");
    }

    @Test
    void generateTrackingToken_shouldReturnBase64UrlSafeTokenWithEntropy() {
        String token1 = trackingService.generateTrackingToken();
        String token2 = trackingService.generateTrackingToken();

        assertNotNull(token1);
        assertNotNull(token2);
        assertNotEquals(token1, token2);
        assertEquals(32, token1.length(), "Token length should be 32 chars (192 bits entropy)");
    }

    @Test
    void sanitizeHtml_shouldStripScriptIframeAndJavascriptProtocols() {
        String dirtyHtml = "<div>Hello</div><script>alert('xss');</script><iframe src='evil.com'></iframe><a href='javascript:alert(1)'>Click</a>";
        String cleanHtml = trackingService.sanitizeHtml(dirtyHtml);

        assertFalse(cleanHtml.contains("<script>"));
        assertFalse(cleanHtml.contains("<iframe>"));
        assertFalse(cleanHtml.contains("javascript:"));
        assertTrue(cleanHtml.contains("href=\"#\""));
    }

    @Test
    void rewriteLinks_shouldRewriteHttpsLinksAndExcludeSpecialSchemes() {
        UUID tenantId = UUID.randomUUID();
        UUID campaignId = UUID.randomUUID();
        String trackingToken = "test-token";

        when(trackedLinkRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        String html = "<a href=\"https://example.com/page\">Link</a><a href=\"mailto:test@example.com\">Mail</a><a href=\"tel:1234\">Tel</a>";
        String rewritten = trackingService.rewriteLinks(html, tenantId, campaignId, trackingToken);

        assertTrue(rewritten.contains("/api/v1/t/c/"));
        assertTrue(rewritten.contains("href=\"mailto:test@example.com\""));
        assertTrue(rewritten.contains("href=\"tel:1234\""));
        assertFalse(rewritten.contains("href=\"https://example.com/page\""));
    }

    @Test
    void injectTrackingPixel_shouldAppendOrInsertBeforeBody() {
        String htmlWithBody = "<html><body><p>Test</p></body></html>";
        String htmlInjected = trackingService.injectTrackingPixel(htmlWithBody, "token-123");

        assertTrue(htmlInjected.contains("<img src="));
        assertTrue(htmlInjected.contains("/api/v1/t/o/token-123.png"));
        assertTrue(htmlInjected.contains("</body>"));
    }

    @Test
    void getUnsubscribeHeaders_shouldReturnRfc8058Headers() {
        Map<String, String> headers = trackingService.getUnsubscribeHeaders("token-xyz");

        assertEquals("<http://localhost:3000/api/v1/u/token-xyz>", headers.get("List-Unsubscribe"));
        assertEquals("List-Unsubscribe=One-Click", headers.get("List-Unsubscribe-Post"));
    }
}
