package com.chatcrmlite.backend.controllers.platform;

import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.TenantSubscription;
import com.chatcrmlite.backend.repositories.TenantRepository;
import com.chatcrmlite.backend.repositories.TenantSubscriptionRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.platform.PlatformAuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.*;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Platform tenant management — view, search, lifecycle actions.
 *
 * All routes require ROLE_PLATFORM_ADMIN (enforced by PlatformAuthFilter + SecurityConfig).
 */
@RestController
@RequestMapping("/api/v1/platform/tenants")
@Transactional(readOnly = true)
public class PlatformTenantController {

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final TenantSubscriptionRepository subscriptionRepository;
    private final PlatformAuditService auditService;
    private final com.chatcrmlite.backend.services.platform.PlatformTenantProfileService profileService;

    public PlatformTenantController(TenantRepository tenantRepository,
                                    UserRepository userRepository,
                                    TenantSubscriptionRepository subscriptionRepository,
                                    PlatformAuditService auditService,
                                    com.chatcrmlite.backend.services.platform.PlatformTenantProfileService profileService) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.auditService = auditService;
        this.profileService = profileService;
    }

    /** Paginated + searchable tenant list. */
    @GetMapping
    public ResponseEntity<Map<String, Object>> listTenants(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sort,
            @RequestParam(defaultValue = "DESC") String direction,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String lifecycleStatus,
            @RequestParam(required = false) String businessType,
            HttpServletRequest request) {

        List<Tenant> all = tenantRepository.findAll();

        // Apply filters
        List<Tenant> filtered = all.stream()
            .filter(t -> lifecycleStatus == null
                || (t.getLifecycleStatus() != null && t.getLifecycleStatus().name().equalsIgnoreCase(lifecycleStatus)))
            .filter(t -> businessType == null
                || businessType.equalsIgnoreCase(t.getBusinessType()))
            .filter(t -> search == null || search.isBlank()
                || containsIgnoreCase(t.getBusinessName(), search))
            .collect(Collectors.toList());

        // Sort
        filtered.sort(Comparator.comparing(
            t -> t.getCreatedAt() != null ? t.getCreatedAt() : LocalDateTime.MIN
        ));
        if ("DESC".equalsIgnoreCase(direction)) Collections.reverse(filtered);

        // Paginate
        int total = filtered.size();
        int start = Math.min(page * size, total);
        int end = Math.min(start + size, total);
        List<Map<String, Object>> content = filtered.subList(start, end).stream()
            .map(this::toSummary)
            .toList();

        auditService.record("VIEWED_TENANTS", "SUCCESS", "Tenant", null,
            "{\"page\":" + page + ",\"search\":\"" + (search != null ? search : "") + "\"}", request);

        return ResponseEntity.ok(Map.<String, Object>of(
            "content", content,
            "page", page,
            "size", size,
            "totalElements", total,
            "totalPages", (int) Math.ceil((double) total / size),
            "hasNext", end < total
        ));
    }

    /** Comprehensive 360° Tenant Profile Summary */
    @GetMapping("/{id}/profile")
    public ResponseEntity<com.chatcrmlite.backend.dtos.platform.TenantProfileSummaryDto> getTenantProfile(
            @PathVariable UUID id, HttpServletRequest request) {
        auditService.record("VIEWED_TENANT_PROFILE", "SUCCESS", "Tenant", id.toString(), "{}", request);
        return ResponseEntity.ok(profileService.getTenantProfileSummary(id));
    }

    /** Timezone-Aware Multi-Channel Usage Analytics */
    @GetMapping("/{id}/analytics")
    public ResponseEntity<com.chatcrmlite.backend.dtos.platform.TenantMultiChannelAnalyticsDto> getTenantAnalytics(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "CURRENT_MONTH") String range,
            HttpServletRequest request) {
        auditService.record("VIEWED_TENANT_ANALYTICS", "SUCCESS", "Tenant", id.toString(), "{\"range\":\"" + range + "\"}", request);
        return ResponseEntity.ok(profileService.getTenantAnalytics(id, range));
    }

    /** Paginated & Filterable Team Member Roster */
    @GetMapping("/{id}/members")
    public ResponseEntity<com.chatcrmlite.backend.dtos.platform.TenantMemberRosterDto> getTenantMembers(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "ALL") String role,
            @RequestParam(required = false) String search,
            HttpServletRequest request) {
        auditService.record("VIEWED_TENANT_MEMBERS", "SUCCESS", "Tenant", id.toString(), "{\"page\":" + page + ",\"role\":\"" + role + "\"}", request);
        return ResponseEntity.ok(profileService.getTenantMembers(id, page, size, role, search));
    }

    /** Full tenant detail. */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getTenant(@PathVariable UUID id, HttpServletRequest request) {
        return tenantRepository.findById(id).map(tenant -> {
            auditService.record("VIEWED_TENANT", "SUCCESS", "Tenant", id.toString(), "{}", request);
            Map<String, Object> detail = new LinkedHashMap<>(toSummary(tenant));
            detail.put("address", tenant.getAddress());
            detail.put("aboutUs", tenant.getAboutUs());
            detail.put("logoUrl", tenant.getLogoUrl());
            detail.put("primaryColor", tenant.getPrimaryColor());
            detail.put("onboardingCompleted", tenant.getOnboardingCompleted());
            detail.put("suspensionReason", tenant.getSuspensionReason());
            detail.put("suspendedAt", tenant.getSuspendedAt());
            detail.put("userCount", userRepository.findAll().stream()
                .filter(u -> u.getTenant() != null && u.getTenant().getId().equals(id)).count());
            return ResponseEntity.ok(detail);
        }).orElse(ResponseEntity.notFound().build());
    }

    /** Users belonging to a specific tenant. */
    @GetMapping("/{id}/users")
    public ResponseEntity<List<Map<String, Object>>> getTenantUsers(@PathVariable UUID id) {
        List<Map<String, Object>> users = userRepository.findAll().stream()
            .filter(u -> u.getTenant() != null && u.getTenant().getId().equals(id))
            .map(u -> Map.<String, Object>of(
                "id", u.getId(),
                "email", u.getEmail(),
                "displayName", u.getDisplayName() != null ? u.getDisplayName() : "",
                "role", u.getRole() != null ? u.getRole().name() : "USER",
                "status", u.getAccountStatus() != null ? u.getAccountStatus().name() : "ACTIVE",
                "createdAt", u.getCreatedAt() != null ? u.getCreatedAt().toString() : ""
            ))
            .toList();
        return ResponseEntity.ok(users);
    }

    /** Subscription for a tenant. */
    @GetMapping("/{id}/subscription")
    public ResponseEntity<Object> getTenantSubscription(@PathVariable UUID id) {
        return subscriptionRepository.findAll().stream()
            .filter(s -> s.getTenant() != null && s.getTenant().getId().equals(id))
            .findFirst()
            .map(s -> ResponseEntity.ok((Object) Map.<String, Object>of(
                "id", s.getId(),
                "planName", s.getPlan() != null ? s.getPlan().getName() : "N/A",
                "status", s.getStatus().name(),
                "billingCycle", s.getBillingCycle().name(),
                "currentPeriodStart", s.getCurrentPeriodStart().toString(),
                "currentPeriodEnd", s.getCurrentPeriodEnd().toString()
            )))
            .orElse(ResponseEntity.ok(Map.<String, Object>of("message", "No subscription found")));
    }

    // ── Lifecycle Actions ──────────────────────────────────────────────────────

    public record SuspendRequest(String reason) {}

    @Transactional
    @PostMapping("/{id}/suspend")
    public ResponseEntity<Map<String, Object>> suspend(
            @PathVariable UUID id,
            @RequestBody SuspendRequest body,
            HttpServletRequest request) {
        return updateLifecycle(id, Tenant.LifecycleStatus.SUSPENDED,
            body != null && body.reason() != null ? body.reason() : "", request, "SUSPENDED_TENANT");
    }

    @Transactional
    @PostMapping("/{id}/activate")
    public ResponseEntity<Map<String, Object>> activate(@PathVariable UUID id, HttpServletRequest request) {
        return updateLifecycle(id, Tenant.LifecycleStatus.ACTIVE, null, request, "ACTIVATED_TENANT");
    }

    @Transactional
    @PostMapping("/{id}/lock")
    public ResponseEntity<Map<String, Object>> lock(@PathVariable UUID id, HttpServletRequest request) {
        return updateLifecycle(id, Tenant.LifecycleStatus.LOCKED, null, request, "LOCKED_TENANT");
    }

    @Transactional
    @PostMapping("/{id}/archive")
    public ResponseEntity<Map<String, Object>> archive(@PathVariable UUID id, HttpServletRequest request) {
        return updateLifecycle(id, Tenant.LifecycleStatus.ARCHIVED, null, request, "ARCHIVED_TENANT");
    }

    public record PatchStatusRequest(String status, String reason) {}

    /** Soft-delete via PATCH /status — NOT HTTP DELETE. Behavior is explicit in body. */
    @Transactional
    @PatchMapping("/{id}/status")
    public ResponseEntity<Map<String, Object>> patchStatus(
            @PathVariable UUID id,
            @RequestBody PatchStatusRequest body,
            HttpServletRequest request) {

        if (body == null || body.status() == null) {
            return ResponseEntity.badRequest().body(Map.<String, Object>of("message", "status is required"));
        }

        Tenant.LifecycleStatus status;
        try {
            status = Tenant.LifecycleStatus.valueOf(body.status().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.<String, Object>of("message", "Invalid status: " + body.status()));
        }

        return updateLifecycle(id, status, body.reason(), request, "STATUS_CHANGED_TO_" + body.status().toUpperCase());
    }

    public record QuotaOverrideRequest(String resource, Integer newLimit) {}

    @PostMapping("/{id}/quota/override")
    public ResponseEntity<Map<String, Object>> overrideQuota(
            @PathVariable UUID id,
            @RequestBody QuotaOverrideRequest body,
            HttpServletRequest request) {
        String resource = body != null ? body.resource() : "unknown";
        Integer newLimit = body != null ? body.newLimit() : 0;
        auditService.record("CHANGED_QUOTA", "SUCCESS", "Tenant", id.toString(),
            "{\"resource\":\"" + resource + "\",\"newLimit\":" + newLimit + "}", request);
        // TODO: Persist to quota override table in Phase 2
        return ResponseEntity.ok(Map.<String, Object>of("message", "Quota override applied (simulated)", "tenantId", id));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> updateLifecycle(
            UUID id, Tenant.LifecycleStatus newStatus,
            String reason, HttpServletRequest request, String auditAction) {

        return tenantRepository.findById(id).map(tenant -> {
            String previousStatus = tenant.getLifecycleStatus() != null
                ? tenant.getLifecycleStatus().name() : "ACTIVE";
            tenant.setLifecycleStatus(newStatus);
            if (reason != null && !reason.isBlank()) tenant.setSuspensionReason(reason);
            if (newStatus == Tenant.LifecycleStatus.SUSPENDED) tenant.setSuspendedAt(LocalDateTime.now());
            if (newStatus == Tenant.LifecycleStatus.ACTIVE) {
                tenant.setSuspensionReason(null);
                tenant.setSuspendedAt(null);
            }
            tenantRepository.save(tenant);

            auditService.record(auditAction, "SUCCESS", "Tenant", id.toString(),
                "{\"previousStatus\":\"" + previousStatus + "\",\"newStatus\":\"" + newStatus.name()
                + "\",\"reason\":\"" + (reason != null ? reason : "") + "\"}", request);

            return ResponseEntity.ok(Map.<String, Object>of(
                "message", "Tenant status updated to " + newStatus.name(),
                "tenantId", id,
                "lifecycleStatus", newStatus.name()
            ));
        }).orElse(ResponseEntity.notFound().build());
    }

    private Map<String, Object> toSummary(Tenant t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("businessName", t.getBusinessName());
        m.put("businessType", t.getBusinessType());
        m.put("businessSubType", t.getBusinessSubType());
        m.put("planType", t.getPlanType() != null ? t.getPlanType().name() : "FREE");
        m.put("lifecycleStatus", t.getLifecycleStatus() != null ? t.getLifecycleStatus().name() : "ACTIVE");
        m.put("onboardingCompleted", t.getOnboardingCompleted());
        m.put("createdAt", t.getCreatedAt() != null ? t.getCreatedAt().toString() : "");
        return m;
    }

    private boolean containsIgnoreCase(String value, String search) {
        return value != null && value.toLowerCase().contains(search.toLowerCase());
    }
}
