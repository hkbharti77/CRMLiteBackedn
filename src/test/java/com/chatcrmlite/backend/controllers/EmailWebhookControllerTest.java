package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.models.email.EmailCampaignRecipient;
import com.chatcrmlite.backend.repositories.email.EmailCampaignRecipientRepository;
import com.chatcrmlite.backend.repositories.email.EmailProviderEventRepository;
import com.chatcrmlite.backend.services.email.EmailSuppressionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmailWebhookControllerTest {

    @Mock
    private EmailProviderEventRepository providerEventRepository;

    @Mock
    private EmailCampaignRecipientRepository recipientRepository;

    @Mock
    private EmailSuppressionService suppressionService;

    @InjectMocks
    private EmailWebhookController controller;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final String TEST_SECRET = "secret_webhook_key_12345";

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "configuredWebhookSecret", TEST_SECRET);
    }

    @Test
    @DisplayName("Should accept valid webhook with correct X-Webhook-Secret header")
    void testValidWebhookWithSecret() throws Exception {
        String payloadJson = """
            {
                "event_id": "evt_001",
                "provider": "resend",
                "tracking_token": "token_abc",
                "event_type": "delivered"
            }
            """;

        EmailCampaignRecipient recipient = new EmailCampaignRecipient();
        recipient.setId(UUID.randomUUID());
        recipient.setTenantId(UUID.randomUUID());
        recipient.setDeliveryStatus(EmailCampaignRecipient.DeliveryStatus.SENT);

        when(providerEventRepository.existsByProviderAndProviderEventId("resend", "evt_001")).thenReturn(false);
        when(recipientRepository.findByTrackingToken("token_abc")).thenReturn(Optional.of(recipient));

        ResponseEntity<Void> response = controller.handleGenericWebhook(
                TEST_SECRET, null, objectMapper.readTree(payloadJson));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(recipientRepository).save(any(EmailCampaignRecipient.class));
    }

    @Test
    @DisplayName("Should reject webhook when X-Webhook-Secret is missing")
    void testMissingSecret() throws Exception {
        String payloadJson = """
            {
                "event_id": "evt_002",
                "provider": "resend",
                "tracking_token": "token_abc",
                "event_type": "bounced"
            }
            """;

        ResponseEntity<Void> response = controller.handleGenericWebhook(
                null, null, objectMapper.readTree(payloadJson));

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        verifyNoInteractions(providerEventRepository, recipientRepository, suppressionService);
    }

    @Test
    @DisplayName("Should reject webhook when X-Webhook-Secret is invalid")
    void testInvalidSecret() throws Exception {
        String payloadJson = """
            {
                "event_id": "evt_003",
                "provider": "resend",
                "tracking_token": "token_abc",
                "event_type": "bounced"
            }
            """;

        ResponseEntity<Void> response = controller.handleGenericWebhook(
                "wrong_secret", null, objectMapper.readTree(payloadJson));

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        verifyNoInteractions(providerEventRepository, recipientRepository, suppressionService);
    }

    @Test
    @DisplayName("Should reject webhook when server secret is not configured")
    void testUnconfiguredServerSecret() throws Exception {
        ReflectionTestUtils.setField(controller, "configuredWebhookSecret", "");

        String payloadJson = """
            {
                "event_id": "evt_004",
                "provider": "resend",
                "tracking_token": "token_abc",
                "event_type": "bounced"
            }
            """;

        ResponseEntity<Void> response = controller.handleGenericWebhook(
                TEST_SECRET, null, objectMapper.readTree(payloadJson));

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        verifyNoInteractions(providerEventRepository, recipientRepository, suppressionService);
    }
}
