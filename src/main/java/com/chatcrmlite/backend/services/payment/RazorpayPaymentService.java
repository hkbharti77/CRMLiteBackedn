package com.chatcrmlite.backend.services.payment;

import com.razorpay.RazorpayClient;
import com.razorpay.Order;
import com.razorpay.Utils;
import org.json.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
@Slf4j
public class RazorpayPaymentService {

    @Value("${razorpay.key.id}")
    private String keyId;

    @Value("${razorpay.key.secret}")
    private String keySecret;

    /**
     * Creates a new Razorpay Order for subscription billing.
     * Amount is supplied in standard currency value (e.g. ₹999.00) and converted to paise.
     */
    public String createOrder(BigDecimal amount, String currency, String receiptId) {
        try {
            log.info("💳 Creating Razorpay Order for amount: {} {}, receipt: {}", amount, currency, receiptId);
            RazorpayClient client = new RazorpayClient(keyId, keySecret);
            
            JSONObject options = new JSONObject();
            // Convert to sub-units (paise)
            int amountInPaise = amount.multiply(BigDecimal.valueOf(100)).intValue();
            options.put("amount", amountInPaise);
            options.put("currency", currency);
            options.put("receipt", receiptId);
            options.put("payment_capture", 1); // Capture payments automatically

            Order order = client.orders.create(options);
            String orderId = order.get("id").toString();
            log.info("✅ Razorpay Order created successfully. ID: {}", orderId);
            return orderId;
        } catch (Exception e) {
            log.error("❌ Failed to create Razorpay Order", e);
            throw new RuntimeException("Error communicating with Razorpay: " + e.getMessage());
        }
    }

    public String getKeyId() {
        return keyId;
    }

    public String getKeySecret() {
        return keySecret;
    }

    /**
     * Verifies the integrity of webhook payloads received from Razorpay.
     */
    public boolean verifySignature(String payload, String signature, String webhookSecret) {
        try {
            return Utils.verifySignature(payload, signature, webhookSecret);
        } catch (Exception e) {
            log.error("❌ Razorpay signature verification failed", e);
            return false;
        }
    }

    /**
     * Verifies the client-side checkout payment signature (orderId, paymentId, signature) with key secret.
     */
    public boolean verifyPaymentSignature(String orderId, String paymentId, String signature) {
        if (orderId == null || paymentId == null || signature == null || keySecret == null) {
            log.warn("⚠️ Cannot verify signature: missing fields (orderId: {}, paymentId: {}, hasSignature: {}, hasSecret: {})",
                    orderId, paymentId, signature != null, keySecret != null);
            return false;
        }

        try {
            JSONObject options = new JSONObject();
            options.put("razorpay_order_id", orderId);
            options.put("razorpay_payment_id", paymentId);
            options.put("razorpay_signature", signature);
            return Utils.verifyPaymentSignature(options, keySecret);
        } catch (Exception e) {
            log.warn("⚠️ Razorpay SDK verifyPaymentSignature failed, falling back to direct HMAC calculation: {}", e.getMessage());
            try {
                String payload = orderId + "|" + paymentId;
                javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
                mac.init(new javax.crypto.spec.SecretKeySpec(keySecret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
                byte[] hash = mac.doFinal(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                StringBuilder hex = new StringBuilder();
                for (byte b : hash) {
                    hex.append(String.format("%02x", b));
                }
                return hex.toString().equalsIgnoreCase(signature);
            } catch (Exception ex) {
                log.error("❌ HMAC calculation failed: {}", ex.getMessage());
                return false;
            }
        }
    }
}
