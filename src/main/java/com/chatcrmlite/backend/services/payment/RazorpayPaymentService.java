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
}
