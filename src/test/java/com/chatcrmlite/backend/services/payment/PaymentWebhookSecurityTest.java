package com.chatcrmlite.backend.services.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
class PaymentWebhookSecurityTest {

    @InjectMocks
    private StripePaymentService stripePaymentService;

    @InjectMocks
    private RazorpayPaymentService razorpayPaymentService;

    private static final String VALID_SECRET = "whsec_test_secret_abc123";

    @Test
    @DisplayName("Stripe: Should reject webhook verification when secret is null or dummy")
    void testStripeRejectDummySecret() {
        ReflectionTestUtils.setField(stripePaymentService, "webhookSecret", "dummy_stripe_webhook_secret");
        boolean result = stripePaymentService.verifyWebhookSignature("{}", "t=123,v1=abc");
        assertFalse(result);

        ReflectionTestUtils.setField(stripePaymentService, "webhookSecret", "");
        boolean result2 = stripePaymentService.verifyWebhookSignature("{}", "t=123,v1=abc");
        assertFalse(result2);

        ReflectionTestUtils.setField(stripePaymentService, "webhookSecret", null);
        boolean result3 = stripePaymentService.verifyWebhookSignature("{}", "t=123,v1=abc");
        assertFalse(result3);
    }

    @Test
    @DisplayName("Stripe: Should reject webhook verification when signature header is missing")
    void testStripeRejectMissingHeader() {
        ReflectionTestUtils.setField(stripePaymentService, "webhookSecret", VALID_SECRET);
        boolean result = stripePaymentService.verifyWebhookSignature("{}", null);
        assertFalse(result);

        boolean result2 = stripePaymentService.verifyWebhookSignature("{}", "   ");
        assertFalse(result2);
    }

    @Test
    @DisplayName("Razorpay: Should reject webhook verification when secret is null or dummy")
    void testRazorpayRejectDummySecret() {
        boolean result = razorpayPaymentService.verifySignature("{}", "sig", "dummy_razorpay_webhook_secret");
        assertFalse(result);

        boolean result2 = razorpayPaymentService.verifySignature("{}", "sig", "");
        assertFalse(result2);

        boolean result3 = razorpayPaymentService.verifySignature("{}", "sig", null);
        assertFalse(result3);
    }

    @Test
    @DisplayName("Razorpay: Should reject webhook verification when signature is missing")
    void testRazorpayRejectMissingSignature() {
        boolean result = razorpayPaymentService.verifySignature("{}", null, VALID_SECRET);
        assertFalse(result);

        boolean result2 = razorpayPaymentService.verifySignature("{}", "   ", VALID_SECRET);
        assertFalse(result2);
    }

    @Test
    @DisplayName("Razorpay: Should verify valid HMAC-SHA256 signature with valid secret")
    void testRazorpayValidSignature() throws Exception {
        String payload = "{\"event\":\"payment.captured\"}";
        
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(VALID_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hashBytes = hmac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        String signature = HexFormat.of().formatHex(hashBytes);

        boolean result = razorpayPaymentService.verifySignature(payload, signature, VALID_SECRET);
        assertTrue(result);
    }
}
