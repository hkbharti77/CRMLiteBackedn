package com.chatcrmlite.backend.controllers.platform;

import com.chatcrmlite.backend.services.platform.PlatformAnalyticsService;
import com.chatcrmlite.backend.services.platform.PlatformAuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/platform/analytics")
@Transactional(readOnly = true)
public class PlatformAnalyticsController {

    private final PlatformAnalyticsService analyticsService;
    private final PlatformAuditService auditService;

    public PlatformAnalyticsController(PlatformAnalyticsService analyticsService,
                                       PlatformAuditService auditService) {
        this.analyticsService = analyticsService;
        this.auditService = auditService;
    }

    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> overview(HttpServletRequest request) {
        auditService.record("VIEWED_ANALYTICS", "SUCCESS", "System", null, "{\"type\":\"overview\"}", request);
        return ResponseEntity.ok(analyticsService.getOverview());
    }

    @GetMapping("/growth")
    public ResponseEntity<List<Map<String, Object>>> growth(
            @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(analyticsService.getGrowth(days));
    }

    @GetMapping("/niches")
    public ResponseEntity<List<Map<String, Object>>> niches() {
        return ResponseEntity.ok(analyticsService.getNiches());
    }

    @GetMapping("/subscriptions")
    public ResponseEntity<Map<String, Object>> subscriptions() {
        return ResponseEntity.ok(analyticsService.getSubscriptionStats());
    }

    @GetMapping("/churn")
    public ResponseEntity<Map<String, Object>> churn() {
        return ResponseEntity.ok(analyticsService.getChurn());
    }

    @GetMapping("/operational")
    public ResponseEntity<Map<String, Object>> operational() {
        return ResponseEntity.ok(analyticsService.getOperationalMetrics());
    }

    @GetMapping("/recent-activity")
    public ResponseEntity<Object> recentActivity(
            @RequestParam(defaultValue = "50") int limit,
            HttpServletRequest request) {
        var page = auditService.findRecent(PageRequest.of(0, Math.min(limit, 200)));
        return ResponseEntity.ok(page.getContent());
    }
}
