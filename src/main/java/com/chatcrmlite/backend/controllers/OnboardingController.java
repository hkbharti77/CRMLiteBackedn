package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/v1/onboarding")
public class OnboardingController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WhatsAppConfigRepository whatsappConfigRepository;

    @PostMapping("/submit")
    public ResponseEntity<?> submitOnboarding(
            @AuthenticationPrincipal String email,
            @RequestBody OnboardingRequest request) {

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Update User info
        user.setDisplayName(request.getDisplayName());
        user.setPhone(request.getPhone());
        user.setBusinessName(request.getBusinessName());
        user.setBusinessType(request.getBusinessType());
        user.setBusinessSubType(request.getBusinessSubType());
        user.setAddress(request.getAddress());
        user.setLogoUrl(request.getLogoUrl());
        user.setOnboardingCompleted(true);
        if (request.isConsentAccepted()) {
            user.setConsentAt(LocalDateTime.now());
        }
        userRepository.save(user);

        // Update WhatsApp Config
        WhatsAppConfig config = whatsappConfigRepository.findByUserId(user.getId())
                .orElse(new WhatsAppConfig());

        config.setUser(user);
        config.setPhoneNumberId(request.getPhoneNumberId());
        config.setAccessToken(request.getAccessToken());
        config.setVerifyToken(request.getVerifyToken());
        // wabaId isn't explicitly requested but often needed, set if provided
        if (request.getWabaId() != null) {
            config.setWabaId(request.getWabaId());
        }

        whatsappConfigRepository.save(config);

        return ResponseEntity.ok("Onboarding completed successfully");
    }

    @PostMapping("/skip")
    public ResponseEntity<?> skipOnboarding(
            @AuthenticationPrincipal String email) {

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        user.setOnboardingCompleted(true);
        userRepository.save(user);

        return ResponseEntity.ok("Onboarding skipped");
    }

    @Data
    public static class OnboardingRequest {
        private String displayName;
        private String phone;
        private String businessName;
        private String businessType;
        private String businessSubType;
        private String phoneNumberId;
        private String accessToken;
        private String verifyToken;
        private String wabaId;
        private boolean consentAccepted;
        private String address;
        private String logoUrl;
    }
}
