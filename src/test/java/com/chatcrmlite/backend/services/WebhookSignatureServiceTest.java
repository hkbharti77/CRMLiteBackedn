package com.chatcrmlite.backend.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class WebhookSignatureServiceTest {

    private WebhookSignatureService service;
    private final String testSecret = "test_secret_12345";

    @BeforeEach
    void setUp() {
        service = new WebhookSignatureService();
        ReflectionTestUtils.setField(service, "appSecret", testSecret);
    }

    @Test
    void testValidSignature() throws Exception {
        String payload = "{\"object\":\"whatsapp_business_account\",\"entry\":[]}";
        String expectedSignature = calculateHmac(payload, testSecret);
        String header = "sha256=" + expectedSignature;

        assertTrue(service.verifySignature(payload, header), "Valid signature should pass");
    }

    @Test
    void testInvalidSignature() {
        String payload = "{\"object\":\"whatsapp_business_account\",\"entry\":[]}";
        String header = "sha256=invalid_signature_hex_code";

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
