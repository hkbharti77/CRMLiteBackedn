package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.clients.MetaOnboardingClient;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;

@Slf4j
@RestController
@RequestMapping("/api/v1/integrations/meta/oauth")
@PreAuthorize("@perm.has(authentication, 'SETTINGS_WHATSAPP')")
public class EmbeddedSignupController {

    @Autowired
    private MetaOnboardingClient metaOnboardingClient;

    @Autowired
    private WhatsAppConfigRepository whatsappConfigRepository;

    @Autowired
    private UserRepository userRepository;

    @PostMapping("/exchange")
    public ResponseEntity<?> exchangeToken(
            @AuthenticationPrincipal String email,
            @RequestBody Map<String, String> payload) {
        
        try {
            User user = userRepository.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            String code = payload.get("code");
            if (code == null || code.isBlank()) {
                return ResponseEntity.badRequest().body("code is required");
            }

            // 1. Exchange Code for Long Lived Token
            String longLivedToken = metaOnboardingClient.exchangeForLongLivedToken(code);

            // 2. Fetch Business ID & Expiry
            Map<String, Object> debugData = metaOnboardingClient.debugToken(longLivedToken);
            String businessId = (String) debugData.get("businessId");
            LocalDateTime tokenExpiry = (LocalDateTime) debugData.get("tokenExpiry");

            // 3. Fetch WABA ID
            String wabaId = (String) debugData.get("wabaId");
            if (wabaId == null) {
                wabaId = metaOnboardingClient.fetchWabaId(businessId, longLivedToken);
            }

            // 4. Fetch Phone Number Details
            Map<String, String> phoneDetails = metaOnboardingClient.fetchPhoneNumberDetails(wabaId, longLivedToken);

            // 5. Save to Database
            WhatsAppConfig config = whatsappConfigRepository.findByUserId(user.getId())
                    .orElse(new WhatsAppConfig());

            config.setUser(user);
            config.setAccessToken(longLivedToken);
            config.setTokenExpiry(tokenExpiry);
            config.setBusinessId(businessId);
            config.setWabaId(wabaId);
            config.setPhoneNumberId(phoneDetails.get("phoneNumberId"));
            config.setDisplayPhoneNumber(phoneDetails.get("displayPhoneNumber"));
            config.setVerifiedName(phoneDetails.get("verifiedName"));
            config.setQualityRating(phoneDetails.get("qualityRating"));
            config.setVerificationStatus("VERIFIED"); // Assume verified if we fetched it, or parse from Graph API
            config.setAccountStatus("ACTIVE");

            // Generate a secure Verify Token for Webhooks if it doesn't exist
            if (config.getVerifyToken() == null || config.getVerifyToken().isBlank()) {
                config.setVerifyToken(UUID.randomUUID().toString());
            }

            whatsappConfigRepository.save(config);

            return ResponseEntity.ok(Map.of(
                    "message", "WhatsApp integration successful!",
                    "phoneNumberId", config.getPhoneNumberId(),
                    "verifiedName", config.getVerifiedName()
            ));

        } catch (Exception e) {
            log.error("Embedded Signup Failed", e);
            return ResponseEntity.internalServerError().body("Integration failed: " + e.getMessage());
        }
    }
}
