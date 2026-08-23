package com.chatcrmlite.backend.services.payment;

import com.stripe.Stripe;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
public class StripePaymentService {

    @Value("${stripe.api.key:dummy_stripe_api_key}")
    private String apiKey;

    @Value("${stripe.webhook.secret:dummy_stripe_webhook_secret}")
    private String webhookSecret;

    @Value("${app.public.url:http://localhost:8080}")
    private String publicUrl;

    @PostConstruct
    public void init() {
        Stripe.apiKey = apiKey;
    }

    /**
     * Creates a Stripe Checkout Session for subscription upgrade.
     */
    public String createCheckoutSession(UUID tenantId, String planId, String billingCycle, BigDecimal price, String currency) {
        try {
            log.info("💳 Creating Stripe Checkout Session for tenant: {}, plan: {}, price: {}", tenantId, planId, price);
            
            // Stripe expects amount in cents
            long amountInCents = price.multiply(BigDecimal.valueOf(100)).longValue();

            SessionCreateParams params = SessionCreateParams.builder()
                    .addPaymentMethodType(SessionCreateParams.PaymentMethodType.CARD)
                    .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                    .setSuccessUrl(publicUrl + "/api/v1/public/payment/success?session_id={CHECKOUT_SESSION_ID}")
                    .setCancelUrl(publicUrl + "/api/v1/public/payment/cancel")
                    .addLineItem(SessionCreateParams.LineItem.builder()
                            .setQuantity(1L)
                            .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                                    .setCurrency(currency.toLowerCase())
                                    .setUnitAmount(amountInCents)
                                    .setRecurring(SessionCreateParams.LineItem.PriceData.Recurring.builder()
                                            .setInterval(billingCycle.equalsIgnoreCase("YEARLY") ? 
                                                    SessionCreateParams.LineItem.PriceData.Recurring.Interval.YEAR : 
                                                    SessionCreateParams.LineItem.PriceData.Recurring.Interval.MONTH)
                                            .build())
                                    .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                            .setName("CRMLite " + planId + " Plan (" + billingCycle + ")")
                                            .build())
                                    .build())
                            .build())
                    .putMetadata("tenantId", tenantId.toString())
                    .putMetadata("planId", planId)
                    .putMetadata("billingCycle", billingCycle)
                    .build();

            Session session = Session.create(params);
            log.info("✅ Stripe Checkout Session created. URL: {}", session.getUrl());
            return session.getUrl();
        } catch (Exception e) {
            log.error("❌ Failed to create Stripe Checkout Session", e);
            throw new RuntimeException("Error communicating with Stripe: " + e.getMessage());
        }
    }

    /**
     * Verifies Stripe Webhook signature.
     */
    public boolean verifyWebhookSignature(String payload, String sigHeader) {
        try {
            Webhook.Signature.verifyHeader(payload, sigHeader, webhookSecret, 300L);
            return true;
        } catch (Exception e) {
            log.error("❌ Stripe webhook signature verification failed", e);
            return false;
        }
    }
}
