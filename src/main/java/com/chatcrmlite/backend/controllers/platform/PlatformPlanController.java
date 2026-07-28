package com.chatcrmlite.backend.controllers.platform;

import com.chatcrmlite.backend.models.SubscriptionPlan;
import com.chatcrmlite.backend.repositories.SubscriptionPlanRepository;
import com.chatcrmlite.backend.repositories.TenantSubscriptionRepository;
import com.chatcrmlite.backend.services.platform.PlatformAuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/platform/plans")
public class PlatformPlanController {

    private final SubscriptionPlanRepository subscriptionPlanRepository;
    private final TenantSubscriptionRepository tenantSubscriptionRepository;
    private final PlatformAuditService auditService;

    public PlatformPlanController(SubscriptionPlanRepository subscriptionPlanRepository,
                                  TenantSubscriptionRepository tenantSubscriptionRepository,
                                  PlatformAuditService auditService) {
        this.subscriptionPlanRepository = subscriptionPlanRepository;
        this.tenantSubscriptionRepository = tenantSubscriptionRepository;
        this.auditService = auditService;
    }

    @GetMapping
    public ResponseEntity<List<SubscriptionPlan>> listPlans(HttpServletRequest request) {
        auditService.record("LIST_SUBSCRIPTION_PLANS", "SUCCESS", "Platform", null, "{}", request);
        return ResponseEntity.ok(subscriptionPlanRepository.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<SubscriptionPlan> getPlan(@PathVariable String id) {
        return subscriptionPlanRepository.findById(id.toUpperCase())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> createPlan(@RequestBody SubscriptionPlan plan, HttpServletRequest request) {
        if (plan.getId() == null || plan.getId().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Plan ID is required (e.g., PRO, GROWTH, AGENCY)"));
        }

        String planId = plan.getId().trim().toUpperCase();
        if (subscriptionPlanRepository.existsById(planId)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Subscription plan already exists with ID: " + planId));
        }

        plan.setId(planId);
        if (plan.getName() == null || plan.getName().trim().isEmpty()) {
            plan.setName(planId + " Plan");
        }

        // Set dual pricing defaults if null
        if (plan.getPriceMonthlyInr() == null) plan.setPriceMonthlyInr(plan.getPriceMonthly() != null ? plan.getPriceMonthly() : BigDecimal.ZERO);
        if (plan.getPriceYearlyInr() == null) plan.setPriceYearlyInr(plan.getPriceYearly() != null ? plan.getPriceYearly() : BigDecimal.ZERO);
        if (plan.getPriceMonthlyUsd() == null) plan.setPriceMonthlyUsd(plan.getPriceMonthly() != null ? plan.getPriceMonthly() : BigDecimal.ZERO);
        if (plan.getPriceYearlyUsd() == null) plan.setPriceYearlyUsd(plan.getPriceYearly() != null ? plan.getPriceYearly() : BigDecimal.ZERO);

        SubscriptionPlan saved = subscriptionPlanRepository.save(plan);

        auditService.record("CREATE_SUBSCRIPTION_PLAN", "SUCCESS", "SubscriptionPlan", planId,
                "Created plan: " + saved.getName(), request);

        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updatePlan(@PathVariable String id, @RequestBody SubscriptionPlan planDetails, HttpServletRequest request) {
        String planId = id.toUpperCase();
        SubscriptionPlan plan = subscriptionPlanRepository.findById(planId).orElse(null);
        if (plan == null) {
            return ResponseEntity.notFound().build();
        }

        if (planDetails.getName() != null) plan.setName(planDetails.getName());
        if (planDetails.getPriceMonthlyInr() != null) plan.setPriceMonthlyInr(planDetails.getPriceMonthlyInr());
        if (planDetails.getPriceYearlyInr() != null) plan.setPriceYearlyInr(planDetails.getPriceYearlyInr());
        if (planDetails.getPriceMonthlyUsd() != null) plan.setPriceMonthlyUsd(planDetails.getPriceMonthlyUsd());
        if (planDetails.getPriceYearlyUsd() != null) plan.setPriceYearlyUsd(planDetails.getPriceYearlyUsd());

        if (planDetails.getPriceMonthly() != null) plan.setPriceMonthly(planDetails.getPriceMonthly());
        if (planDetails.getPriceYearly() != null) plan.setPriceYearly(planDetails.getPriceYearly());

        plan.setEmployeeLimit(planDetails.getEmployeeLimit());
        plan.setPrimaryResourceLimit(planDetails.getPrimaryResourceLimit());
        plan.setSecondaryResourceLimit(planDetails.getSecondaryResourceLimit());
        plan.setTicketLimit(planDetails.getTicketLimit());
        plan.setEmailLimit(planDetails.getEmailLimit());
        plan.setHasWhatsapp(planDetails.isHasWhatsapp());
        plan.setHasCustomWidget(planDetails.isHasCustomWidget());
        plan.setHasRagLlm(planDetails.isHasRagLlm());
        plan.setContactUs(planDetails.isContactUs());

        SubscriptionPlan updated = subscriptionPlanRepository.save(plan);

        auditService.record("UPDATE_SUBSCRIPTION_PLAN", "SUCCESS", "SubscriptionPlan", planId,
                "Updated plan settings & prices for: " + updated.getName(), request);

        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deletePlan(@PathVariable String id, HttpServletRequest request) {
        String planId = id.toUpperCase();
        if ("FREE".equals(planId)) {
            return ResponseEntity.badRequest().body(Map.of("error", "The default FREE plan cannot be deleted."));
        }

        SubscriptionPlan plan = subscriptionPlanRepository.findById(planId).orElse(null);
        if (plan == null) {
            return ResponseEntity.notFound().build();
        }

        // Check if any active tenant is assigned to this plan
        long inUseCount = tenantSubscriptionRepository.findAll().stream()
                .filter(s -> s.getPlan() != null && planId.equalsIgnoreCase(s.getPlan().getId()))
                .count();

        if (inUseCount > 0) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", 
                    "Cannot delete plan " + planId + " because " + inUseCount + " tenant(s) are actively assigned to it."));
        }

        subscriptionPlanRepository.delete(plan);

        auditService.record("DELETE_SUBSCRIPTION_PLAN", "SUCCESS", "SubscriptionPlan", planId,
                "Deleted plan: " + planId, request);

        return ResponseEntity.ok(Map.of("message", "Plan deleted successfully", "id", planId));
    }
}
