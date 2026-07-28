package com.chatcrmlite.backend.controllers.platform;

import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.TenantRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.platform.PlatformAuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/platform/subscriptions")
public class PlatformSubscriptionController {

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final PlatformAuditService auditService;

    public PlatformSubscriptionController(TenantRepository tenantRepository,
                                          UserRepository userRepository,
                                          PlatformAuditService auditService) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listSubscriptions(HttpServletRequest request) {
        auditService.record("LIST_SUBSCRIPTIONS", "SUCCESS", "Platform", null, "{}", request);

        List<Tenant> tenants = tenantRepository.findAll();
        List<Map<String, Object>> list = tenants.stream().map(t -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", "sub-" + t.getId().toString().substring(0, 8));
            map.put("tenantId", t.getId().toString());
            map.put("tenant", t.getBusinessName());
            
            String planStr = t.getPlanType() != null ? t.getPlanType().name().toLowerCase() : "free";
            map.put("plan", planStr);
            map.put("status", Boolean.TRUE.equals(t.getOnboardingCompleted()) ? "active" : "trialing");
            
            int price = switch (t.getPlanType() != null ? t.getPlanType().name().toUpperCase() : "FREE") {
                case "ENTERPRISE" -> 19999;
                case "PRO", "SCALE" -> 9999;
                case "GROWTH" -> 4999;
                default -> 2999;
            };
            map.put("mrr", price);
            
            long userCount = userRepository.countByTenantId(t.getId());
            map.put("seatsUsed", userCount);
            map.put("seats", userCount + 5);
            map.put("paymentMethod", "Auto-Debit (UPI)");
            map.put("renewalDate", t.getCreatedAt() != null ? t.getCreatedAt().plusDays(30).toLocalDate().toString() : "2026-08-15");
            return map;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(list);
    }

    @PutMapping("/{tenantId}/plan")
    public ResponseEntity<Map<String, Object>> updatePlan(@PathVariable UUID tenantId,
                                                          @RequestBody Map<String, String> body,
                                                          HttpServletRequest request) {
        String newPlan = body.get("plan");
        if (newPlan == null) return ResponseEntity.badRequest().build();

        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));

        String oldPlan = tenant.getPlanType() != null ? tenant.getPlanType().name() : "FREE";
        try {
            tenant.setPlanType(User.PlanType.valueOf(newPlan.toUpperCase()));
        } catch (IllegalArgumentException e) {
            tenant.setPlanType(User.PlanType.FREE);
        }
        tenantRepository.save(tenant);

        auditService.record("UPDATE_TENANT_PLAN", "SUCCESS", "Tenant", tenantId.toString(),
                String.format("Updated plan from %s to %s", oldPlan, newPlan), request);

        return ResponseEntity.ok(Map.of(
                "tenantId", tenantId.toString(),
                "plan", newPlan,
                "status", "UPDATED"
        ));
    }
}
