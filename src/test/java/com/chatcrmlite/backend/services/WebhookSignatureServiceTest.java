package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class WebhookSignatureServiceTest {

    @Mock
    private WhatsAppConfigRepository configRepository;

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

        WhatsAppConfig config = new WhatsAppConfig();
        config.setAppSecret(testSecret);
        when(configRepository.findByPhoneNumberId(phoneId)).thenReturn(Optional.of(config));

        String expectedSignature = calculateHmac(payload, testSecret);
        String header = "sha256=" + expectedSignature;

        assertTrue(service.verifySignature(payload, header), "Valid signature should pass");
    }

    @Test
    void testInvalidSignature() {
        String payload = "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"changes\":[{\"value\":{\"metadata\":{\"phone_number_id\":\"test-phone-id\"}}}]}]}";
        String header = "sha256=invalid_signature_hex_code";

        WhatsAppConfig config = new WhatsAppConfig();
        config.setAppSecret(testSecret);
        when(configRepository.findByPhoneNumberId(phoneId)).thenReturn(Optional.of(config));

        assertFalse(service.verifySignature(payload, header), "Invalid signature should fail");
    }

    @Test
    void testMissingSignature() {
        String payload = "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"changes\":[{\"value\":{\"metadata\":{\"phone_number_id\":\"test-phone-id\"}}}]}]}";
        assertFalse(service.verifySignature(payload, null), "Missing signature header should fail");
        assertFalse(service.verifySignature(payload, ""), "Empty signature header should fail");
    }

    @Test
    void testMalformedHeader() {
        String payload = "{}";
        assertFalse(service.verifySignature(payload, "invalid_format_no_sha_prefix"), "Malformed header should fail");
        assertFalse(service.verifySignature(payload, "sha1=1234567890abcdef"), "Non-sha256 header prefix should fail");
    }

    @Test
    void testModifiedRequestBody_TamperedPayloadRejected() throws Exception {
        String originalPayload = "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"changes\":[{\"value\":{\"metadata\":{\"phone_number_id\":\"test-phone-id\"}},\"messages\":[{\"id\":\"wamid.123\",\"text\":{\"body\":\"Original text\"}}]}]}]}";

        WhatsAppConfig config = new WhatsAppConfig();
        config.setAppSecret(testSecret);
        when(configRepository.findByPhoneNumberId(phoneId)).thenReturn(Optional.of(config));

        // Generate signature for the original payload
        String originalSignature = "sha256=" + calculateHmac(originalPayload, testSecret);

        // Tamper with the payload (e.g. change message text)
        String tamperedPayload = originalPayload.replace("Original text", "Tampered text");

        assertFalse(service.verifySignature(tamperedPayload, originalSignature),
                "Tampered payload must be rejected even with valid signature for original payload");
    }

    @Test
    void testSignatureCalculatedAgainstExactRawBody() throws Exception {
        // Any change in whitespace or formatting must alter the HMAC and fail verification
        String payloadWithWhitespace = "{\n  \"object\": \"whatsapp_business_account\",\n  \"entry\": [{\"changes\": [{\"value\": {\"metadata\": {\"phone_number_id\": \"test-phone-id\"}}}]}]\n}";

        WhatsAppConfig config = new WhatsAppConfig();
        config.setAppSecret(testSecret);
        when(configRepository.findByPhoneNumberId(phoneId)).thenReturn(Optional.of(config));

        String validSig = "sha256=" + calculateHmac(payloadWithWhitespace, testSecret);
        assertTrue(service.verifySignature(payloadWithWhitespace, validSig), "Raw exact body must verify successfully");

        // Minified or formatted variant of the same JSON
        String minifiedPayload = "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"changes\":[{\"value\":{\"metadata\":{\"phone_number_id\":\"test-phone-id\"}}}]}]}";
        assertFalse(service.verifySignature(minifiedPayload, validSig),
                "Signature must be against exact raw body bytes; reformatting changes HMAC");
    }

    @Test
    void testValidSignature_WithOldInternalMessageTimestamp_Accepted() throws Exception {
        long oldTimestamp = (System.currentTimeMillis() / 1000) - 7200; // 2 hours old
        String payload = "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"changes\":[{\"value\":{\"metadata\":{\"phone_number_id\":\"test-phone-id\"}},\"messages\":[{\"timestamp\":\"" + oldTimestamp + "\"}]}}]}]}";

        WhatsAppConfig config = new WhatsAppConfig();
        config.setAppSecret(testSecret);
        when(configRepository.findByPhoneNumberId(phoneId)).thenReturn(Optional.of(config));

        String header = "sha256=" + calculateHmac(payload, testSecret);

        assertTrue(service.verifySignature(payload, header),
                "Valid webhook with an old internal message timestamp must NOT be rejected by signature verification");
    }

    @Test
    void testValidSignature_WithFutureInternalMessageTimestamp_Accepted() throws Exception {
        long futureTimestamp = (System.currentTimeMillis() / 1000) + 3600; // 1 hour in future
        String payload = "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"changes\":[{\"value\":{\"metadata\":{\"phone_number_id\":\"test-phone-id\"}},\"messages\":[{\"timestamp\":\"" + futureTimestamp + "\"}]}}]}]}";

        WhatsAppConfig config = new WhatsAppConfig();
        config.setAppSecret(testSecret);
        when(configRepository.findByPhoneNumberId(phoneId)).thenReturn(Optional.of(config));

        String header = "sha256=" + calculateHmac(payload, testSecret);

        assertTrue(service.verifySignature(payload, header),
                "Valid webhook with future message timestamp must NOT be rejected by signature verification");
    }

    @Test
    void testRegression_84966sDiffPayloadNotRejected() throws Exception {
        // Production incident scenario: valid Meta retry with message timestamp ~23.6 hours (84966s) old
        long nowSeconds = System.currentTimeMillis() / 1000;
        long productionMessageTimestamp = nowSeconds - 84966;

        String payload = "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"id\":\"WABA_123\",\"changes\":[{\"value\":{\"messaging_product\":\"whatsapp\",\"metadata\":{\"display_phone_number\":\"15551234567\",\"phone_number_id\":\"test-phone-id\"},\"messages\":[{\"from\":\"919999999999\",\"id\":\"wamid.HBgLM...\",\"timestamp\":\"" + productionMessageTimestamp + "\",\"text\":{\"body\":\"Hello CRM\"},\"type\":\"text\"}]},\"field\":\"messages\"}]}]}";

        WhatsAppConfig config = new WhatsAppConfig();
        config.setAppSecret(testSecret);
        when(configRepository.findByPhoneNumberId(phoneId)).thenReturn(Optional.of(config));

        String validSignatureHeader = "sha256=" + calculateHmac(payload, testSecret);

        assertTrue(service.verifySignature(payload, validSignatureHeader),
                "Regression: Webhook payload with 84966s skew must NOT be rejected when cryptographically valid");
    }

    @Test
    void testFallbackToGlobalAppSecretWhenConfigAppSecretMissing() throws Exception {
        String globalSecret = "global_meta_secret_xyz";
        ReflectionTestUtils.setField(service, "globalAppSecret", globalSecret);

        String payload = "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"changes\":[{\"value\":{\"metadata\":{\"phone_number_id\":\"unknown-phone-id\"}}}]}]}";
        when(configRepository.findByPhoneNumberId("unknown-phone-id")).thenReturn(Optional.empty());

        String header = "sha256=" + calculateHmac(payload, globalSecret);

        assertTrue(service.verifySignature(payload, header),
                "Signature verification should fall back to global meta.app-secret if phone config is absent");
    }

    @Test
    void testFallbackToGlobalAppSecretWhenPayloadHasNoPhoneNumberId() throws Exception {
        String globalSecret = "global_meta_secret_xyz";
        ReflectionTestUtils.setField(service, "globalAppSecret", globalSecret);

        // Account update payload without phone_number_id
        String accountUpdatePayload = "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"id\":\"WABA_ID_999\",\"changes\":[{\"value\":{\"event\":\"APPROVED\"},\"field\":\"account_update\"}]}]}";

        String header = "sha256=" + calculateHmac(accountUpdatePayload, globalSecret);

        assertTrue(service.verifySignature(accountUpdatePayload, header),
                "Signature verification should fall back to global secret for account update payloads without phone_number_id");
    }

    @Test
    void testTimestampValidation_DeprecatedAlwaysTrue() {
        // Ensures backward compatibility: isTimestampValid always returns true
        long now = System.currentTimeMillis() / 1000;
        assertTrue(service.isTimestampValid("{\"timestamp\": " + now + "}"));
        assertTrue(service.isTimestampValid("{\"timestamp\": " + (now - 84966) + "}"));
        assertTrue(service.isTimestampValid("{\"timestamp\": " + (now + 86400) + "}"));
        assertTrue(service.isTimestampValid("{}"));
    }

    private String calculateHmac(String payload, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(secretKey);
        byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }
}
