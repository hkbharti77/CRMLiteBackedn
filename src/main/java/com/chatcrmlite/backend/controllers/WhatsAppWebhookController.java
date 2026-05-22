package com.chatcrmlite.backend.controllers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/webhook/whatsapp")
public class WhatsAppWebhookController {
    private static final Logger log = LoggerFactory.getLogger(WhatsAppWebhookController.class);

    @Value("${whatsapp.webhook.skip-signature-verification:false}")
    private boolean skipSignatureVerification;

    @Autowired
    private com.chatcrmlite.backend.services.WebhookIngressService webhookIngressService;

    @Autowired
    private com.chatcrmlite.backend.repositories.WhatsAppConfigRepository whatsappConfigRepository;

    @Autowired
    private com.chatcrmlite.backend.services.WebhookSignatureService signatureService;

    @GetMapping
    public ResponseEntity<String> verifyWebhook(
            @RequestParam("hub.mode") String mode,
            @RequestParam("hub.verify_token") String token,
            @RequestParam("hub.challenge") String challenge) {
        
        // SECURITY: Do not log the verify token value — it's a shared secret
        log.debug("Incoming verify request: mode={}", mode);

        boolean isValid = whatsappConfigRepository.existsByVerifyToken(token.trim());
        
        if ("subscribe".equals(mode.trim()) && isValid) {
            log.info("✅ Webhook verified successfully via Database Config");
            return ResponseEntity.ok(challenge);
        }
        
        log.warn("❌ Webhook verification failed - No matching config found in database");
        return ResponseEntity.status(403).body("Verification failed");
    }

    @PostMapping
    public ResponseEntity<?> handleWebhook(
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody(required = false) String payload,
            @RequestParam(value = "hub.mode",         required = false) String mode,
            @RequestParam(value = "hub.verify_token", required = false) String token,
            @RequestParam(value = "hub.challenge",    required = false) String challenge,
            jakarta.servlet.http.HttpServletRequest request) {

        boolean hasPayload = payload != null && !payload.isBlank();

        if (!hasPayload && "subscribe".equals(mode) && token != null) {
            boolean isValid = whatsappConfigRepository.existsByVerifyToken(token.trim());
            if (isValid) {
                log.info("✅ Webhook verified successfully via POST from IP: {}", request.getRemoteAddr());
                return ResponseEntity.ok(challenge);
            }
            return ResponseEntity.status(403).body("Verification failed");
        }

        if (hasPayload) {
            if (skipSignatureVerification) {
                log.warn("⚠️ [SECURITY] Webhook signature and timestamp verification is SKIPPED due to skip-signature-verification=true");
            } else {
                if (!signatureService.verifySignature(payload, signature)) {
                    log.warn("🛑 [SECURITY] Invalid Webhook Signature from IP: {} | Header: {}", 
                             request.getRemoteAddr(), signature);
                    return ResponseEntity.status(401).body("Invalid signature");
                }

                if (!signatureService.isTimestampValid(payload)) {
                    log.warn("🛑 [SECURITY] Webhook Timestamp Validation Failed from IP: {}", 
                             request.getRemoteAddr());
                    return ResponseEntity.status(401).body("Invalid timestamp");
                }
            }

            log.info("✅ Webhook signature verified or skipped. Enqueueing payload for async processing...");
            webhookIngressService.ingress(payload);
        }
        
        return ResponseEntity.ok().build();
    }
}
