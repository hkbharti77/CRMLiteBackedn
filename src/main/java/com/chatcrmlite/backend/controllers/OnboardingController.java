package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
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

        WhatsAppConfig config = whatsappConfigRepository.findByUserId(user.getId())
                .orElse(new WhatsAppConfig());

        config.setUser(user);
        config.setPhoneNumberId(request.getPhoneNumberId());
        config.setAccessToken(request.getAccessToken());
        config.setVerifyToken(request.getVerifyToken());
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

        public OnboardingRequest() {}

        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public String getPhone() { return phone; }
        public void setPhone(String phone) { this.phone = phone; }
        public String getBusinessName() { return businessName; }
        public void setBusinessName(String businessName) { this.businessName = businessName; }
        public String getBusinessType() { return businessType; }
        public void setBusinessType(String businessType) { this.businessType = businessType; }
        public String getBusinessSubType() { return businessSubType; }
        public void setBusinessSubType(String businessSubType) { this.businessSubType = businessSubType; }
        public String getPhoneNumberId() { return phoneNumberId; }
        public void setPhoneNumberId(String phoneNumberId) { this.phoneNumberId = phoneNumberId; }
        public String getAccessToken() { return accessToken; }
        public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
        public String getVerifyToken() { return verifyToken; }
        public void setVerifyToken(String verifyToken) { this.verifyToken = verifyToken; }
        public String getWabaId() { return wabaId; }
        public void setWabaId(String wabaId) { this.wabaId = wabaId; }
        public boolean isConsentAccepted() { return consentAccepted; }
        public void setConsentAccepted(boolean consentAccepted) { this.consentAccepted = consentAccepted; }
        public String getAddress() { return address; }
        public void setAddress(String address) { this.address = address; }
        public String getLogoUrl() { return logoUrl; }
        public void setLogoUrl(String logoUrl) { this.logoUrl = logoUrl; }
    }
}
