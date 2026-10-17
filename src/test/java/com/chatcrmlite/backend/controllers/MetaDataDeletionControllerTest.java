package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.models.MetaDataDeletionRequest;
import com.chatcrmlite.backend.services.whatsapp.MetaComplianceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MetaDataDeletionControllerTest {

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private MetaComplianceService metaComplianceService;

    @InjectMocks
    private MetaDataDeletionController controller;

    private static final String APP_SECRET = "meta_test_secret_xyz123";

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "appSecret", APP_SECRET);
        ReflectionTestUtils.setField(controller, "publicAppUrl", "https://api.gyanvaniai.online");
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
    @DisplayName("Data Deletion: Should accept valid Meta signed_request and return compliant JSON contract")
    void testValidDataDeletionSignedRequest() throws Exception {
        String payloadJson = """
            {
                "user_id": "meta_usr_12345",
                "algorithm": "HMAC-SHA256",
                "issued_at": 1700000000
            }
            """;

        String signedRequest = createSignedRequest(payloadJson, APP_SECRET);

        when(metaComplianceService.handleDataDeletion(eq("meta_usr_12345"), anyString()))
                .thenReturn(Map.of(
                        "url", "https://api.gyanvaniai.online/api/v1/integrations/meta/data-deletion/status?code=test_code_123",
                        "confirmation_code", "test_code_123"
                ));

        ResponseEntity<Map<String, String>> response = controller.handleDataDeletion(signedRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("test_code_123", response.getBody().get("confirmation_code"));
        assertTrue(response.getBody().get("url").contains("test_code_123"));
        verify(metaComplianceService, times(1)).handleDataDeletion(eq("meta_usr_12345"), anyString());
    }

    @Test
    @DisplayName("Data Deletion: Should reject Meta signed_request with invalid signature")
    void testInvalidSignatureDataDeletion() throws Exception {
        String payloadJson = """
            {
                "user_id": "meta_usr_12345",
                "algorithm": "HMAC-SHA256"
            }
            """;

        String signedRequest = createSignedRequest(payloadJson, "wrong_secret");

        ResponseEntity<Map<String, String>> response = controller.handleDataDeletion(signedRequest);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verifyNoInteractions(metaComplianceService);
    }

    @Test
    @DisplayName("Data Deletion: Should reject missing or empty signed_request")
    void testMissingSignedRequest() {
        ResponseEntity<Map<String, String>> response = controller.handleDataDeletion(null);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());

        ResponseEntity<Map<String, String>> emptyResponse = controller.handleDataDeletion("");
        assertEquals(HttpStatus.BAD_REQUEST, emptyResponse.getStatusCode());
    }

    @Test
    @DisplayName("Data Deletion: Should reject signed_request missing user_id")
    void testMissingUserIdDataDeletion() throws Exception {
        String payloadJson = """
            {
                "algorithm": "HMAC-SHA256",
                "issued_at": 1700000000
            }
            """;

        String signedRequest = createSignedRequest(payloadJson, APP_SECRET);

        ResponseEntity<Map<String, String>> response = controller.handleDataDeletion(signedRequest);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verifyNoInteractions(metaComplianceService);
    }

    @Test
    @DisplayName("Deauthorization: Should accept valid signed_request and execute deauthorization")
    void testValidDeauthorization() throws Exception {
        String payloadJson = """
            {
                "user_id": "meta_usr_99999",
                "algorithm": "HMAC-SHA256",
                "issued_at": 1700000000
            }
            """;

        String signedRequest = createSignedRequest(payloadJson, APP_SECRET);

        when(metaComplianceService.handleDeauthorization("meta_usr_99999", 1700000000L))
                .thenReturn(Map.of("success", true, "message", "Deauthorized"));

        ResponseEntity<Map<String, Object>> response = controller.handleDeauthorize(signedRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue((Boolean) response.getBody().get("success"));
        verify(metaComplianceService, times(1)).handleDeauthorization("meta_usr_99999", 1700000000L);
    }

    @Test
    @DisplayName("Deauthorization: Should reject invalid signature")
    void testInvalidSignatureDeauthorization() throws Exception {
        String payloadJson = """
            {
                "user_id": "meta_usr_99999",
                "algorithm": "HMAC-SHA256"
            }
            """;

        String signedRequest = createSignedRequest(payloadJson, "tampered_secret");

        ResponseEntity<Map<String, Object>> response = controller.handleDeauthorize(signedRequest);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verifyNoInteractions(metaComplianceService);
    }

    @Test
    @DisplayName("Status Endpoint: Returns 200 and clean HTML when request exists")
    void testStatusEndpointSuccess() {
        MetaDataDeletionRequest req = MetaDataDeletionRequest.builder()
                .confirmationCode("valid_code_123")
                .status("COMPLETED")
                .build();

        when(metaComplianceService.getDeletionStatus("valid_code_123")).thenReturn(Optional.of(req));

        ResponseEntity<String> response = controller.getDeletionStatus("valid_code_123");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("Status: COMPLETED"));
        assertTrue(response.getBody().contains("valid_code_123"));
    }

    @Test
    @DisplayName("Status Endpoint: Returns 404 Not Found when code does not exist (prevents enumeration)")
    void testStatusEndpointNotFound() {
        when(metaComplianceService.getDeletionStatus("nonexistent_code")).thenReturn(Optional.empty());

        ResponseEntity<String> response = controller.getDeletionStatus("nonexistent_code");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("Not Found"));
    }

    @Test
    @DisplayName("Status Endpoint: Returns 404 when no code is provided")
    void testStatusEndpointEmptyCode() {
        ResponseEntity<String> response = controller.getDeletionStatus("");
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());

        ResponseEntity<String> nullResponse = controller.getDeletionStatus(null);
        assertEquals(HttpStatus.NOT_FOUND, nullResponse.getStatusCode());
    }
}
