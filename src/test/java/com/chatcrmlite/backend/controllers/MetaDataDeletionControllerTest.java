package com.chatcrmlite.backend.controllers;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@ExtendWith(MockitoExtension.class)
class MetaDataDeletionControllerTest {

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private MetaDataDeletionController controller;

    private static final String APP_SECRET = "meta_test_secret_xyz123";

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "appSecret", APP_SECRET);
    }

    private String createSignedRequest(String payloadJson, String secret) throws Exception {
        String encodedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] sigBytes = hmac.doFinal(encodedPayload.getBytes(StandardCharsets.UTF_8));
        String encodedSig = Base64.getUrlEncoder().withoutPadding().encodeToString(sigBytes);

        return encodedSig + "." + encodedPayload;
    }

    @Test
    @DisplayName("Should accept valid Meta signed_request with correct HMAC-SHA256 signature")
    void testValidSignedRequest() throws Exception {
        String payloadJson = """
            {
                "user_id": "meta_usr_12345",
                "algorithm": "HMAC-SHA256",
                "issued_at": 1700000000
            }
            """;

        String signedRequest = createSignedRequest(payloadJson, APP_SECRET);

        ResponseEntity<Map<String, String>> response = controller.handleDataDeletion(signedRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertNotNull(response.getBody().get("confirmation_code"));
        assertNotNull(response.getBody().get("url"));
    }

    @Test
    @DisplayName("Should reject Meta signed_request with invalid signature")
    void testInvalidSignatureSignedRequest() throws Exception {
        String payloadJson = """
            {
                "user_id": "meta_usr_12345",
                "algorithm": "HMAC-SHA256"
            }
            """;

        String signedRequest = createSignedRequest(payloadJson, "wrong_secret");

        ResponseEntity<Map<String, String>> response = controller.handleDataDeletion(signedRequest);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    @DisplayName("Should reject malformed signed_request")
    void testMalformedSignedRequest() {
        ResponseEntity<Map<String, String>> response = controller.handleDataDeletion("not_a_valid_signed_request");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    @DisplayName("Should reject signed_request when app secret is not configured")
    void testUnconfiguredAppSecret() throws Exception {
        ReflectionTestUtils.setField(controller, "appSecret", "");

        String payloadJson = """
            {
                "user_id": "meta_usr_12345",
                "algorithm": "HMAC-SHA256"
            }
            """;

        String signedRequest = createSignedRequest(payloadJson, APP_SECRET);

        ResponseEntity<Map<String, String>> response = controller.handleDataDeletion(signedRequest);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }
}
