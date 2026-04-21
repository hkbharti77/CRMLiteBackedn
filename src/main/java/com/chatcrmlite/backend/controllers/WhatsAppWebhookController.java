package com.chatcrmlite.backend.controllers;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/webhook/whatsapp")
public class WhatsAppWebhookController {

    @Autowired
    private com.chatcrmlite.backend.services.WhatsAppService whatsappService;

    @Autowired
    private com.chatcrmlite.backend.repositories.WhatsAppConfigRepository whatsappConfigRepository;

    @GetMapping
    public ResponseEntity<String> verifyWebhook(
            @RequestParam("hub.mode") String mode,
            @RequestParam("hub.verify_token") String token,
            @RequestParam("hub.challenge") String challenge) {
        
        log.info("🔐 Incoming verify request: mode={}, token={}", mode, token);

        // STRICT: Only verify against the database
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
            @RequestBody(required = false) String payload,
            @RequestParam(value = "hub.mode",         required = false) String mode,
            @RequestParam(value = "hub.verify_token", required = false) String token,
            @RequestParam(value = "hub.challenge",    required = false) String challenge) {

        // ── Case 1: Verification request sent via POST (non-standard but happens in some tools) ──
        if ("subscribe".equals(mode) && token != null) {
            boolean isValid = whatsappConfigRepository.existsByVerifyToken(token.trim());
            if (isValid) {
                log.info("✅ Webhook verified successfully via POST");
                return ResponseEntity.ok(challenge);
            }
            return ResponseEntity.status(403).body("Verification failed");
        }

        // ── Case 2: Actual WhatsApp Message Payload ──
        if (payload != null && !payload.isBlank()) {
            whatsappService.processWebhook(payload);
        }
        
        return ResponseEntity.ok().build();
    }
}
