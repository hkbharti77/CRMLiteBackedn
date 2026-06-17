package com.chatcrmlite.backend.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.Mock;
import org.mockito.InjectMocks;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class WebhookSignatureServiceTest {

    @Mock
    private com.chatcrmlite.backend.repositories.WhatsAppConfigRepository configRepository;

    @InjectMocks
    private WebhookSignatureService service;

    private final String testSecret = "test_secret_12345";
    private final String phoneId = "test-phone-id";

    @BeforeEach
    void setUp() {
        org.mockito.MockitoAnnotations.openMocks(this);
    }

    @Test
    void testValidSignature() throws Exception {
        String payload = "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"changes\":[{\"value\":{\"metadata\":{\"phone_number_id\":\"test-phone-id\"}}}]}]}";
        
        com.chatcrmlite.backend.models.WhatsAppConfig config = new com.chatcrmlite.backend.models.WhatsAppConfig();
        config.setAppSecret(testSecret);
        org.mockito.Mockito.when(configRepository.findByPhoneNumberId(phoneId))
                .thenReturn(java.util.Optional.of(config));

        String expectedSignature = calculateHmac(payload, testSecret);
        String header = "sha256=" + expectedSignature;

        assertTrue(service.verifySignature(payload, header), "Valid signature should pass");
    }

    @Test
    void testInvalidSignature() {
        String payload = "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"changes\":[{\"value\":{\"metadata\":{\"phone_number_id\":\"test-phone-id\"}}}]}]}";
        String header = "sha256=invalid_signature_hex_code";

        com.chatcrmlite.backend.models.WhatsAppConfig config = new com.chatcrmlite.backend.models.WhatsAppConfig();
        config.setAppSecret(testSecret);
        org.mockito.Mockito.when(configRepository.findByPhoneNumberId(phoneId))
                .thenReturn(java.util.Optional.of(config));

        assertFalse(service.verifySignature(payload, header), "Invalid signature should fail");
    }

    @Test
    void testMalformedHeader() {
        String payload = "{}";
        assertFalse(service.verifySignature(payload, "invalid_format_no_sha_prefix"), "Malformed header should fail");
        assertFalse(service.verifySignature(payload, null), "Missing header should fail");
    }

    @Test
    void testTimestampValidation() {
        long now = System.currentTimeMillis() / 1000;
        String validPayload = "{\"timestamp\": " + now + "}";
        String oldPayload = "{\"timestamp\": " + (now - 600) + "}"; // 10 mins old
        String futurePayload = "{\"timestamp\": " + (now + 600) + "}"; // 10 mins future

        assertTrue(service.isTimestampValid(validPayload), "Recent timestamp should be valid");
        assertFalse(service.isTimestampValid(oldPayload), "Old timestamp should be invalid");
        assertFalse(service.isTimestampValid(futurePayload), "Future timestamp should be invalid");
    }

    @Test
    void testTimestampValidationInQuotes() {
        long now = System.currentTimeMillis() / 1000;
        String validPayload = "{\"timestamp\": \"" + now + "\"}";
        assertTrue(service.isTimestampValid(validPayload), "Timestamp in quotes should be valid");
    }

    @Test
    void testTimestampValidationNoTimestamp() {
        String payload = "{\"object\": \"whatsapp\"}";
        assertTrue(service.isTimestampValid(payload), "Payload without timestamp should be allowed (fail-safe)");
    }

    private String calculateHmac(String payload, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(secretKey);
        byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }
}
