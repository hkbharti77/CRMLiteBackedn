package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.models.journey.CustomerJourney;
import com.chatcrmlite.backend.models.journey.CustomerJourneyVersion;
import com.chatcrmlite.backend.services.journey.CustomerJourneyService;
import com.chatcrmlite.backend.utils.TenantResolver;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/journeys")
@Slf4j
@RequiredArgsConstructor
public class CustomerJourneyController {

    private final CustomerJourneyService journeyService;
    private final TenantResolver tenantResolver;

    @PostMapping
    public ResponseEntity<?> createJourney(
            @RequestBody CreateJourneyRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String headerTenantId,
            Authentication authentication) {

        String businessId = tenantResolver.resolveBusinessId(headerTenantId, null);
        if (businessId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Tenant ID missing"));
        }

        CustomerJourney journey = journeyService.createJourney(
                businessId, request.getName(), request.getDescription(), request.getTriggerEvent(), request.getReentryMode());
        return ResponseEntity.ok(Map.of("data", journey));
    }

    @GetMapping
    public ResponseEntity<?> getJourneys(
            @RequestHeader(value = "X-Tenant-ID", required = false) String headerTenantId,
            Authentication authentication) {

        String businessId = tenantResolver.resolveBusinessId(headerTenantId, null);
        if (businessId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Tenant ID missing"));
        }

        List<CustomerJourney> journeys = journeyService.getJourneys(businessId);
        return ResponseEntity.ok(Map.of("data", journeys));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getJourney(
            @PathVariable UUID id,
            @RequestHeader(value = "X-Tenant-ID", required = false) String headerTenantId,
            Authentication authentication) {

        String businessId = tenantResolver.resolveBusinessId(headerTenantId, null);
        return journeyService.getJourney(businessId, id)
                .map(j -> ResponseEntity.ok(Map.of("data", j)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/versions")
    public ResponseEntity<?> createVersion(
            @PathVariable UUID id,
            @RequestBody CreateVersionRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String headerTenantId,
            Authentication authentication) {

        String businessId = tenantResolver.resolveBusinessId(headerTenantId, null);
        CustomerJourneyVersion version = journeyService.createJourneyVersion(businessId, id, request.getDefinitionJson());
        return ResponseEntity.ok(Map.of("data", version));
    }

    @GetMapping("/{id}/versions")
    public ResponseEntity<?> getVersions(@PathVariable UUID id) {
        List<CustomerJourneyVersion> versions = journeyService.getJourneyVersions(id);
        return ResponseEntity.ok(Map.of("data", versions));
    }

    @PostMapping("/{id}/versions/{versionId}/publish")
    public ResponseEntity<?> publishVersion(
            @PathVariable UUID id,
            @PathVariable UUID versionId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String headerTenantId,
            Authentication authentication) {

        String businessId = tenantResolver.resolveBusinessId(headerTenantId, null);
        CustomerJourney journey = journeyService.publishJourneyVersion(businessId, id, versionId);
        return ResponseEntity.ok(Map.of("data", journey));
    }

    @Data
    public static class CreateJourneyRequest {
        private String name;
        private String description;
        private String triggerEvent;
        private String reentryMode;
    }

    @Data
    public static class CreateVersionRequest {
        private String definitionJson;
    }
}
