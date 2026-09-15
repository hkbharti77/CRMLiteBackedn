package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.config.RateLimitConfig;
import com.chatcrmlite.backend.models.email.EmailCampaignRecipient;
import com.chatcrmlite.backend.models.email.EmailTrackedLink;
import com.chatcrmlite.backend.repositories.email.EmailCampaignRecipientRepository;
import com.chatcrmlite.backend.repositories.email.EmailRecipientEventRepository;
import com.chatcrmlite.backend.repositories.email.EmailTrackedLinkRepository;
import com.chatcrmlite.backend.services.email.EmailSuppressionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class EmailTrackingControllerTest {

    @Mock
    private EmailCampaignRecipientRepository recipientRepository;

    @Mock
    private EmailRecipientEventRepository eventRepository;

    @Mock
    private EmailTrackedLinkRepository linkRepository;

    @Mock
    private EmailSuppressionService suppressionService;

    @Mock
    private RateLimitConfig rateLimitConfig;

    @InjectMocks
    private EmailTrackingController trackingController;

    private MockHttpServletRequest mockRequest;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        mockRequest = new MockHttpServletRequest();
        mockRequest.setRemoteAddr("127.0.0.1");

        when(rateLimitConfig.tryConsume(any(), any())).thenReturn(true);
    }

    @Test
    void trackOpen_shouldReturnPngAndExecuteAtomicUpdate() {
        String token = "valid-token";
        UUID recipientId = UUID.randomUUID();
        EmailCampaignRecipient recipient = EmailCampaignRecipient.builder()
                .tenantId(UUID.randomUUID())
                .campaignId(UUID.randomUUID())
                .email("test@example.com")
                .trackingToken(token)
                .build();
        recipient.setId(recipientId);

        when(recipientRepository.findByTrackingToken(token)).thenReturn(Optional.of(recipient));

        ResponseEntity<byte[]> response = trackingController.trackOpen(token, mockRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(MediaType.IMAGE_PNG, response.getHeaders().getContentType());
        verify(eventRepository, times(1)).save(any());
        verify(recipientRepository, times(1)).updateFirstOpenedAt(eq(recipientId), any());
    }

    @Test
    void trackClickSingle_shouldRedirectToStoredDestinationUrlOnly() {
        String linkToken = "link-123";
        String destination = "https://gyanvaniai.online/pricing";
        UUID campaignId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();

        EmailTrackedLink link = EmailTrackedLink.builder()
                .tenantId(UUID.randomUUID())
                .campaignId(campaignId)
                .linkToken(linkToken)
                .destinationUrl(destination)
                .build();

        EmailCampaignRecipient recipient = EmailCampaignRecipient.builder()
                .tenantId(UUID.randomUUID())
                .campaignId(campaignId)
                .email("user@example.com")
                .trackingToken(linkToken)
                .build();
        recipient.setId(recipientId);

        when(linkRepository.findByLinkToken(linkToken)).thenReturn(Optional.of(link));
        when(recipientRepository.findByTrackingToken(linkToken)).thenReturn(Optional.of(recipient));

        ResponseEntity<Void> response = trackingController.trackClickSingle(linkToken, mockRequest);

        assertEquals(HttpStatus.FOUND, response.getStatusCode());
        assertEquals(destination, response.getHeaders().getLocation().toString());
        verify(recipientRepository, times(1)).updateFirstClickedAt(eq(recipientId), any());
    }

    @Test
    void handleUnsubscribeGet_shouldReturnHtmlConfirmationWithoutSuppressing() {
        String token = "unsub-token";
        EmailCampaignRecipient recipient = EmailCampaignRecipient.builder()
                .email("unsub@example.com")
                .build();

        when(recipientRepository.findByTrackingToken(token)).thenReturn(Optional.of(recipient));

        ResponseEntity<String> response = trackingController.handleUnsubscribeGet(token, mockRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains("Unsubscribe Request"));
        assertTrue(response.getBody().contains("unsub@example.com"));
        verify(suppressionService, never()).addSuppression(any(), any(), any(), any(), any());
    }

    @Test
    void handleUnsubscribePost_shouldPerformIdempotentSuppression() {
        String token = "unsub-token";
        UUID tenantId = UUID.randomUUID();
        UUID campaignId = UUID.randomUUID();

        EmailCampaignRecipient recipient = EmailCampaignRecipient.builder()
                .tenantId(tenantId)
                .campaignId(campaignId)
                .email("unsub@example.com")
                .build();

        when(recipientRepository.findByTrackingToken(token)).thenReturn(Optional.of(recipient));

        ResponseEntity<Void> response = trackingController.handleUnsubscribePost(token, mockRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(suppressionService, times(1)).addSuppression(eq(tenantId), eq("unsub@example.com"), any(), eq(campaignId), any());
    }
}
