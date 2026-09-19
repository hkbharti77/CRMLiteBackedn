package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.dto.payments.PaymentCapabilities;
import com.chatcrmlite.backend.dto.payments.TenantPaymentConfigDto;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.enums.PaymentIntegrationType;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.payments.PaymentProvider;
import com.chatcrmlite.backend.services.payments.PaymentProviderFactory;
import com.chatcrmlite.backend.services.payments.TenantPaymentConfigService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenant/payments/config")
@Tag(name = "Payment Configurations", description = "Tenant payment gateway integrations and credentials management")
@Slf4j
@RequiredArgsConstructor
public class TenantPaymentConfigController {

    private final TenantPaymentConfigService configService;
    private final PaymentProviderFactory providerFactory;
    private final UserRepository userRepository;

    @GetMapping
    public ResponseEntity<List<TenantPaymentConfigDto>> listConfigs(@AuthenticationPrincipal String email) {
        UUID tenantId = getTenantId(email);
        return ResponseEntity.ok(configService.listConfigs(tenantId));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'SUPER_ADMIN') or isAuthenticated()")
    public ResponseEntity<TenantPaymentConfigDto> saveConfig(
        @AuthenticationPrincipal String email,
        @RequestBody TenantPaymentConfigDto dto
    ) {
        UUID tenantId = getTenantId(email);
        return ResponseEntity.ok(configService.saveOrUpdateConfig(tenantId, dto));
    }

    @PostMapping("/{id}/regenerate-key")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'SUPER_ADMIN') or isAuthenticated()")
    public ResponseEntity<TenantPaymentConfigDto> regenerateWebhookKey(
        @AuthenticationPrincipal String email,
        @PathVariable("id") UUID configId
    ) {
        UUID tenantId = getTenantId(email);
        return ResponseEntity.ok(configService.regenerateWebhookKey(tenantId, configId));
    }

    @GetMapping("/capabilities")
    public ResponseEntity<Map<String, PaymentCapabilities>> getCapabilities() {
        Map<String, PaymentCapabilities> caps = new HashMap<>();
        for (PaymentIntegrationType type : PaymentIntegrationType.values()) {
            try {
                PaymentProvider provider = providerFactory.getProvider(type);
                caps.put(type.name(), provider.getCapabilities());
            } catch (Exception ignored) {
            }
        }
        return ResponseEntity.ok(caps);
    }

    private UUID getTenantId(String email) {
        if (email == null) {
            throw new IllegalStateException("Unauthenticated request: user email is null");
        }
        User user = userRepository.findByEmailWithTenant(email)
            .orElseThrow(() -> new IllegalStateException("User not found: " + email));
        if (user.getTenant() == null) {
            throw new IllegalStateException("User does not belong to any tenant");
        }
        return user.getTenant().getId();
    }
}
