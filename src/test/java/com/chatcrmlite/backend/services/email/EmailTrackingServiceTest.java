package com.chatcrmlite.backend.services.email;

import com.chatcrmlite.backend.models.email.EmailTrackedLink;
import com.chatcrmlite.backend.repositories.email.EmailTrackedLinkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EmailTrackingServiceTest {

    @Mock
    private EmailTrackedLinkRepository linkRepository;

    @InjectMocks
    private EmailTrackingService trackingService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testTokenGenerationIsSecureAndRandom() {
        String token1 = trackingService.generateTrackingToken();
        String token2 = trackingService.generateTrackingToken();
        
        assertNotNull(token1);
        assertNotNull(token2);
        assertNotEquals(token1, token2, "Tokens should be unique");
        assertEquals(32, token1.length(), "Tokens should be 32 chars long");
        assertTrue(token1.matches("^[a-zA-Z0-9_-]+$"), "Tokens should be URL safe alphanumeric");
    }

    @Test
    void testRewriteLinksInjectsTracking() {
        UUID tenantId = UUID.randomUUID();
        UUID campaignId = UUID.randomUUID();
        String trackingToken = trackingService.generateTrackingToken();
        
        String html = "<html><body><a href=\"https://example.com\">Click here</a></body></html>";
        
        when(linkRepository.save(any(EmailTrackedLink.class))).thenAnswer(i -> i.getArguments()[0]);
        
        String rewritten = trackingService.rewriteLinks(html, tenantId, campaignId, trackingToken);
        
        assertTrue(rewritten.contains("/api/v1/t/c/" + trackingToken + "?l="));
        assertFalse(rewritten.contains("href=\"https://example.com\""));
        verify(linkRepository, times(1)).save(any(EmailTrackedLink.class));
    }

    @Test
    void testInjectTrackingPixel() {
        String trackingToken = trackingService.generateTrackingToken();
        String html = "<html><body><p>Hello</p></body></html>";
        
        String injected = trackingService.injectTrackingPixel(html, trackingToken);
        
        assertTrue(injected.contains("<img src="));
        assertTrue(injected.contains("/api/v1/t/o/" + trackingToken + ".png"));
        assertTrue(injected.contains("display:none"));
    }
}
