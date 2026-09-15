package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.dto.EmailSuppressionDTO;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.email.EmailSuppressionList;
import com.chatcrmlite.backend.models.email.EmailSuppressionList.SuppressionReason;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.security.TenantContext;
import com.chatcrmlite.backend.services.email.EmailSuppressionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/email-suppressions")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class EmailSuppressionController {

    private final EmailSuppressionService suppressionService;
    private final UserRepository userRepository;

    @GetMapping
    public ResponseEntity<List<EmailSuppressionDTO>> getSuppressions(Authentication authentication) {
        UUID tenantId = getTenantIdFromAuth(authentication);
        List<EmailSuppressionList> list = suppressionService.getSuppressions(tenantId);
        List<EmailSuppressionDTO> dtos = list.stream().map(s -> EmailSuppressionDTO.builder()
                .id(s.getId())
                .email(s.getEmail())
                .reason(s.getReason())
                .sourceCampaignId(s.getSourceCampaignId())
                .createdAt(s.getCreatedAt())
                .createdBy(s.getCreatedBy())
                .build()).collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    @PostMapping
    public ResponseEntity<?> addSuppression(@RequestBody Map<String, String> body, Authentication authentication) {
        UUID tenantId = getTenantIdFromAuth(authentication);
        String email = body.get("email");
        if (email == null || email.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Email is required"));
        }

        String reasonStr = body.getOrDefault("reason", "MANUAL");
        SuppressionReason reason;
        try {
            reason = SuppressionReason.valueOf(reasonStr.toUpperCase());
        } catch (Exception e) {
            reason = SuppressionReason.MANUAL;
        }

        User user = getUserFromAuth(authentication);
        UUID userId = user != null ? user.getId() : null;

        suppressionService.addSuppression(tenantId, email, reason, null, userId);
        return ResponseEntity.ok(Map.of("message", "Email added to suppression list successfully"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteSuppression(@PathVariable UUID id, Authentication authentication) {
        UUID tenantId = getTenantIdFromAuth(authentication);
        boolean deleted = suppressionService.deleteSuppression(tenantId, id);
        if (!deleted) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("message", "Suppression removed successfully"));
    }

    private UUID getTenantIdFromAuth(Authentication authentication) {
        UUID ctxId = TenantContext.getTenantId();
        if (ctxId != null) return ctxId;
        User user = getUserFromAuth(authentication);
        return user != null && user.getTenant() != null ? user.getTenant().getId() : null;
    }

    private User getUserFromAuth(Authentication authentication) {
        if (authentication == null) return null;
        return userRepository.findByEmail(authentication.getName()).orElse(null);
    }
}
