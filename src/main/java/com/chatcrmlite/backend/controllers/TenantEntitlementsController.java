package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.dtos.entitlements.TenantEffectiveEntitlementsDTO;
import com.chatcrmlite.backend.models.entitlements.EntitlementCatalog;
import com.chatcrmlite.backend.models.entitlements.EntitlementDefinition;
import com.chatcrmlite.backend.security.TenantContext;
import com.chatcrmlite.backend.services.tenant.EntitlementResolverService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class TenantEntitlementsController {

    private final EntitlementResolverService entitlementResolverService;

    @GetMapping("/tenants/me/entitlements")
    public ResponseEntity<TenantEffectiveEntitlementsDTO> getMyTenantEntitlements() {
        UUID tenantId = TenantContext.getTenantId();
        TenantEffectiveEntitlementsDTO dto = entitlementResolverService.getTenantEffectiveEntitlements(tenantId);
        return ResponseEntity.ok(dto);
    }

    @GetMapping("/entitlements/catalog")
    public ResponseEntity<List<EntitlementDefinition>> getEntitlementsCatalog() {
        return ResponseEntity.ok(EntitlementCatalog.getAll());
    }
}
