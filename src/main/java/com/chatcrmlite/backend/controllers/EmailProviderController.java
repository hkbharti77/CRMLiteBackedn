package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.models.EmailProvider;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.email.EmailProviderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/email-providers")
@RequiredArgsConstructor
public class EmailProviderController {

    private final EmailProviderService emailProviderService;
    private final UserRepository userRepository;

    private String resolveBusinessId(String email) {
        if (email != null && !email.isBlank()) {
            return userRepository.findByEmailWithTenant(email)
                    .map(u -> (u.getTenant() != null && u.getTenant().getId() != null) ? u.getTenant().getId().toString() : u.getId().toString())
                    .orElse("demo-business-123");
        }
        return "demo-business-123";
    }

    @GetMapping
    public ResponseEntity<List<EmailProvider>> getProviders(@AuthenticationPrincipal String email) {
        String businessId = resolveBusinessId(email);
        return ResponseEntity.ok(emailProviderService.getProviders(businessId));
    }

    @PostMapping
    public ResponseEntity<EmailProvider> createProvider(
            @AuthenticationPrincipal String email,
            @RequestBody EmailProvider provider) {
        String businessId = resolveBusinessId(email);
        provider.setBusinessId(businessId);
        return ResponseEntity.ok(emailProviderService.saveProvider(provider));
    }

    @PutMapping("/{id}")
    public ResponseEntity<EmailProvider> updateProvider(
            @AuthenticationPrincipal String email,
            @PathVariable String id,
            @RequestBody EmailProvider provider) {
        String businessId = resolveBusinessId(email);
        provider.setId(id);
        provider.setBusinessId(businessId);
        return ResponseEntity.ok(emailProviderService.saveProvider(provider));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProvider(
            @AuthenticationPrincipal String email,
            @PathVariable String id) {
        String businessId = resolveBusinessId(email);
        emailProviderService.deleteProvider(id, businessId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/test")
    public ResponseEntity<Map<String, Boolean>> testConnection(
            @AuthenticationPrincipal String email,
            @RequestBody EmailProvider provider,
            @RequestParam String testEmail) {
        String businessId = resolveBusinessId(email);
        if (provider.getBusinessId() == null || provider.getBusinessId().isEmpty()) {
            provider.setBusinessId(businessId);
        }
        boolean success = emailProviderService.testConnection(provider, testEmail);
        return ResponseEntity.ok(Map.of("success", success));
    }
}
