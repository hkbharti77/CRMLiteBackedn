package com.chatcrmlite.backend.controllers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/webhook/whatsapp")
@Tag(name = "WhatsApp Webhook", description = "Endpoints for Meta WhatsApp Webhook Integration")
public class WhatsAppWebhookController {
    private static final Logger log = LoggerFactory.getLogger(WhatsAppWebhookController.class);

    @Value("${whatsapp.webhook.skip-signature-verification:false}")
    private boolean skipSignatureVerification;

    @Value("${whatsapp.verify-token}")
    private String globalVerifyToken;

    @Autowired
    private com.chatcrmlite.backend.services.WebhookIngressService webhookIngressService;

    @Autowired
    private com.chatcrmlite.backend.repositories.WhatsAppConfigRepository whatsappConfigRepository;

    @Autowired
    private com.chatcrmlite.backend.services.WebhookSignatureService signatureService;

    @GetMapping
    public ResponseEntity<String> verifyWebhook(
            @Parameter(hidden = true) @RequestParam(value = "hub.mode", required = false) String mode,
            @Parameter(hidden = true) @RequestParam(value = "hub.verify_token", required = false) String token,
            @Parameter(hidden = true) @RequestParam(value = "hub.challenge", required = false) String challenge) {
        
        if (mode == null || token == null || challenge == null) {
            log.warn("❌ Webhook GET request missing required parameters (mode, token, challenge)");
            return ResponseEntity.badRequest().body("Missing required parameters");
        }

        // SECURITY: Do not log the verify token value — it's a shared secret
        log.debug("Incoming verify request: mode={}", mode);

        boolean isValid = whatsappConfigRepository.existsByVerifyToken(token.trim()) || token.trim().equals(globalVerifyToken);
        
        if ("subscribe".equals(mode.trim()) && isValid) {
            log.info("✅ Webhook verified successfully");
            return ResponseEntity.ok(challenge);
        }
        
        log.warn("❌ Webhook verification failed - No matching config found in database and does not match global token");
        return ResponseEntity.status(403).body("Verification failed");
    }

    @PostMapping
    public ResponseEntity<?> handleWebhook(
            @Parameter(hidden = true) @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody(required = false) String payload,
            @Parameter(hidden = true) @RequestParam(value = "hub.mode",         required = false) String mode,
            @Parameter(hidden = true) @RequestParam(value = "hub.verify_token", required = false) String token,
            @Parameter(hidden = true) @RequestParam(value = "hub.challenge",    required = false) String challenge,
            @Parameter(hidden = true) jakarta.servlet.http.HttpServletRequest request) {

        boolean hasPayload = payload != null && !payload.isBlank();

        if (!hasPayload && "subscribe".equals(mode) && token != null) {
            boolean isValid = whatsappConfigRepository.existsByVerifyToken(token.trim()) || token.trim().equals(globalVerifyToken);
            if (isValid) {
                log.info("✅ Webhook verified successfully via POST from IP: {}", request.getRemoteAddr());
                return ResponseEntity.ok(challenge);
            }
            return ResponseEntity.status(403).body("Verification failed");
        }

        if (hasPayload) {
            if (skipSignatureVerification) {
                log.warn("⚠️ [SECURITY] Webhook signature verification is SKIPPED due to skip-signature-verification=true");
            } else {
                if (!signatureService.verifySignature(payload, signature)) {
                    log.warn("🛑 [SECURITY] Invalid Webhook Signature from IP: {} | Header: {}", 
                             request.getRemoteAddr(), signature);
                    return ResponseEntity.status(401).body("Invalid signature");
                }
            }

            log.info("✅ Webhook signature verified or skipped. Enqueueing payload for async processing...");
            webhookIngressService.ingress(payload);
        }
        
        return ResponseEntity.ok().build();
    }
}
