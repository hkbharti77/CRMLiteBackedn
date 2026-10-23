package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.config.RateLimitConfig;
import com.chatcrmlite.backend.services.whatsapp.campaign.WhatsAppLinkTrackingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.net.URI;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class WhatsAppTrackingControllerTest {

    @Mock
    private WhatsAppLinkTrackingService linkTrackingService;

    @Mock
    private RateLimitConfig rateLimitConfig;

    @InjectMocks
    private WhatsAppTrackingController controller;

    private MockHttpServletRequest mockRequest;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        mockRequest = new MockHttpServletRequest();
        mockRequest.setRemoteAddr("203.0.113.50");

        when(rateLimitConfig.tryConsume(any(), eq(RateLimitConfig.Tier.PUBLIC))).thenReturn(true);
    }

    @Test
    @DisplayName("trackClickAndRedirect should return HTTP 302 Found with Location header and strict no-cache headers")
    void trackClickAndRedirect_shouldReturn302WithLocation() {
        String token = "validTok12345";
        String destinationUrl = "https://example.com/promo/festive-deal";

        when(linkTrackingService.resolveAndRecordClick(eq(token), eq("203.0.113.50")))
                .thenReturn(Optional.of(destinationUrl));

        ResponseEntity<Void> response = controller.trackClickAndRedirect(token, mockRequest);

        assertNotNull(response);
        assertEquals(HttpStatus.FOUND, response.getStatusCode());
        assertEquals(URI.create(destinationUrl), response.getHeaders().getLocation());
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        assertTrue(response.getHeaders().getCacheControl().contains("no-cache"));
        assertEquals("no-cache", response.getHeaders().getPragma());

        verify(linkTrackingService, times(1)).resolveAndRecordClick(eq(token), eq("203.0.113.50"));
    }

    @Test
    @DisplayName("trackClickAndRedirect should use X-Forwarded-For IP address when present behind proxy")
    void trackClickAndRedirect_shouldExtractProxyIp() {
        String token = "validTok12345";
        String destinationUrl = "https://example.com/promo";
        mockRequest.addHeader("X-Forwarded-For", "198.51.100.25, 10.0.0.1");

        when(linkTrackingService.resolveAndRecordClick(eq(token), eq("198.51.100.25")))
                .thenReturn(Optional.of(destinationUrl));

        ResponseEntity<Void> response = controller.trackClickAndRedirect(token, mockRequest);

        assertNotNull(response);
        assertEquals(HttpStatus.FOUND, response.getStatusCode());
        verify(rateLimitConfig, times(1)).tryConsume(eq("198.51.100.25"), eq(RateLimitConfig.Tier.PUBLIC));
        verify(linkTrackingService, times(1)).resolveAndRecordClick(eq(token), eq("198.51.100.25"));
    }

    @Test
    @DisplayName("trackClickAndRedirect should return HTTP 404 Not Found when token does not exist")
    void trackClickAndRedirect_shouldReturn404WhenTokenNotFound() {
        String token = "invalidToken";
        when(linkTrackingService.resolveAndRecordClick(eq(token), any()))
                .thenReturn(Optional.empty());

        ResponseEntity<Void> response = controller.trackClickAndRedirect(token, mockRequest);

        assertNotNull(response);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNull(response.getHeaders().getLocation());
    }

    @Test
    @DisplayName("trackClickAndRedirect should return HTTP 429 Too Many Requests when IP rate limit is exceeded")
    void trackClickAndRedirect_shouldReturn429WhenRateLimited() {
        when(rateLimitConfig.tryConsume(eq("203.0.113.50"), eq(RateLimitConfig.Tier.PUBLIC))).thenReturn(false);

        ResponseEntity<Void> response = controller.trackClickAndRedirect("anyToken", mockRequest);

        assertNotNull(response);
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        verify(linkTrackingService, never()).resolveAndRecordClick(any(), any());
    }
}
