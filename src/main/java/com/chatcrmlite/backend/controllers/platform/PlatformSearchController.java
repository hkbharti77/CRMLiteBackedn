package com.chatcrmlite.backend.controllers.platform;

import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.TenantRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.platform.PlatformAuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Global search endpoint for the Owner Panel Cmd+K overlay.
 * Returns up to 5 matching tenants and 5 matching users.
 */
@RestController
@RequestMapping("/api/v1/platform/search")
@Transactional(readOnly = true)
public class PlatformSearchController {

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final PlatformAuditService auditService;

    public PlatformSearchController(TenantRepository tenantRepository,
                                    UserRepository userRepository,
                                    PlatformAuditService auditService) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> search(@RequestParam String q, HttpServletRequest request) {
        if (q == null || q.trim().length() < 2) {
            return ResponseEntity.badRequest().body(Map.of("message", "Search query must be at least 2 characters"));
        }

        String search = q.toLowerCase();
        List<Map<String, Object>> tenantResults = new ArrayList<>();
        List<Map<String, Object>> userResults = new ArrayList<>();

        // Find tenants
        List<Tenant> tenants = tenantRepository.findAll();
        for (Tenant t : tenants) {
            if (t.getBusinessName() != null && t.getBusinessName().toLowerCase().contains(search) ||
                t.getId().toString().toLowerCase().contains(search)) {
                
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", t.getId());
                map.put("type", "TENANT");
                map.put("name", t.getBusinessName());
                map.put("status", t.getLifecycleStatus() != null ? t.getLifecycleStatus().name() : "ACTIVE");
                tenantResults.add(map);
                
                if (tenantResults.size() >= 5) break;
            }
        }

        // Find users
        List<User> users = userRepository.findAll();
        for (User u : users) {
            if ((u.getEmail() != null && u.getEmail().toLowerCase().contains(search)) ||
                (u.getDisplayName() != null && u.getDisplayName().toLowerCase().contains(search)) ||
                u.getId().toString().toLowerCase().contains(search)) {
                
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", u.getId());
                map.put("type", "USER");
                map.put("name", u.getDisplayName() != null ? u.getDisplayName() : u.getEmail());
                map.put("email", u.getEmail());
                map.put("tenantId", u.getTenant() != null ? u.getTenant().getId() : null);
                map.put("status", u.getAccountStatus() != null ? u.getAccountStatus().name() : "ACTIVE");
                userResults.add(map);
                
                if (userResults.size() >= 5) break;
            }
        }

        auditService.record("SEARCHED", "SUCCESS", "System", null, "{\"query\":\"" + search + "\"}", request);

        return ResponseEntity.ok(Map.of(
            "tenants", tenantResults,
            "users", userResults,
            "total", tenantResults.size() + userResults.size()
        ));
    }
}
