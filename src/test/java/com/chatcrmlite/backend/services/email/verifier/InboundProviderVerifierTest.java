package com.chatcrmlite.backend.services.email.verifier;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InboundProviderVerifierTest {

    @Mock
    private HttpServletRequest request;

    private GenericInboundVerifier genericVerifier;
    private MailgunInboundVerifier mailgunVerifier;
    private SendGridInboundVerifier sendGridVerifier;

    private final String secretKey = "super-secret-webhook-key-123";

    @BeforeEach
    void setUp() {
        genericVerifier = new GenericInboundVerifier();
        ReflectionTestUtils.setField(genericVerifier, "configuredSecret", secretKey);

        mailgunVerifier = new MailgunInboundVerifier(genericVerifier);
        sendGridVerifier = new SendGridInboundVerifier(genericVerifier);
    }

    @Test
    @DisplayName("GenericVerifier: Accept valid secret header")
    void testGenericVerifierSuccess() {
        Map<String, String> headers = Map.of("x-webhook-secret", secretKey);
        boolean result = genericVerifier.verify(request, headers, "{}");
        assertTrue(result);
    }

    @Test
    @DisplayName("GenericVerifier: Reject invalid secret header")
    void testGenericVerifierInvalidSecret() {
        Map<String, String> headers = Map.of("x-webhook-secret", "wrong-secret");
        boolean result = genericVerifier.verify(request, headers, "{}");
        assertFalse(result);
    }

    @Test
    @DisplayName("GenericVerifier: Reject missing secret header")
    void testGenericVerifierMissingHeader() {
        Map<String, String> headers = new HashMap<>();
        boolean result = genericVerifier.verify(request, headers, "{}");
        assertFalse(result);
    }

    @Test
    @DisplayName("MailgunVerifier: Validate correct HMAC SHA-256 signature")
    void testMailgunVerifierSuccess() throws Exception {
        String signingKey = "mg-signing-key-999";
        ReflectionTestUtils.setField(mailgunVerifier, "signingKey", signingKey);

        String timestamp = "1700000000";
        String token = "random-mg-token-456";

        Mac hmac = Mac.getInstance("HmacSHA256");
        SecretKeySpec keySpec = new SecretKeySpec(signingKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        hmac.init(keySpec);
        byte[] hmacBytes = hmac.doFinal((timestamp + token).getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : hmacBytes) sb.append(String.format("%02x", b));
        String validSignature = sb.toString();

        when(request.getParameter("timestamp")).thenReturn(timestamp);
        when(request.getParameter("token")).thenReturn(token);
        when(request.getParameter("signature")).thenReturn(validSignature);

        boolean result = mailgunVerifier.verify(request, new HashMap<>(), "");
        assertTrue(result);
    }

    @Test
    @DisplayName("MailgunVerifier: Reject tampered signature")
    void testMailgunVerifierTamperedSignature() {
        String signingKey = "mg-signing-key-999";
        ReflectionTestUtils.setField(mailgunVerifier, "signingKey", signingKey);

        when(request.getParameter("timestamp")).thenReturn("1700000000");
        when(request.getParameter("token")).thenReturn("token");
        when(request.getParameter("signature")).thenReturn("fake-signature");

        boolean result = mailgunVerifier.verify(request, new HashMap<>(), "");
        assertFalse(result);
    }

    @Test
    @DisplayName("SendGridVerifier: Reject when signature headers are missing")
    void testSendGridVerifierMissingHeaders() {
        ReflectionTestUtils.setField(sendGridVerifier, "verificationKey", "fake-key");
        boolean result = sendGridVerifier.verify(request, new HashMap<>(), "{}");
        assertFalse(result);
    }
}
