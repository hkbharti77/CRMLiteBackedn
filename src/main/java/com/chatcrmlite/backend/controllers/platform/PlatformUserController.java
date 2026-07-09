package com.chatcrmlite.backend.controllers.platform;

import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.platform.PlatformAuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/platform/users")
@Transactional(readOnly = true)
public class PlatformUserController {

    private final UserRepository userRepository;
    private final PlatformAuditService auditService;

    public PlatformUserController(UserRepository userRepository, PlatformAuditService auditService) {
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> listUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) UUID tenantId,
            HttpServletRequest request) {

        var all = userRepository.findAll().stream()
            .filter(u -> tenantId == null || (u.getTenant() != null && u.getTenant().getId().equals(tenantId)))
            .filter(u -> status == null || (u.getAccountStatus() != null && u.getAccountStatus().name().equalsIgnoreCase(status)))
            .filter(u -> role == null || (u.getRole() != null && u.getRole().name().equalsIgnoreCase(role)))
            .filter(u -> search == null || search.isBlank()
                || contains(u.getEmail(), search)
                || contains(u.getDisplayName(), search)
                || contains(u.getPhone(), search))
            .collect(Collectors.toList());

        int total = all.size();
        int start = Math.min(page * size, total);
        int end = Math.min(start + size, total);

        List<Map<String, Object>> content = all.subList(start, end).stream().map(u -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.getId());
            m.put("email", u.getEmail());
            m.put("displayName", u.getDisplayName() != null ? u.getDisplayName() : "");
            m.put("phone", u.getPhone() != null ? u.getPhone() : "");
            m.put("role", u.getRole() != null ? u.getRole().name() : "USER");
            m.put("status", u.getAccountStatus() != null ? u.getAccountStatus().name() : "ACTIVE");
            m.put("tenantId", u.getTenant() != null ? u.getTenant().getId() : null);
            m.put("tenantName", u.getTenant() != null ? u.getTenant().getBusinessName() : "");
            m.put("createdAt", u.getCreatedAt() != null ? u.getCreatedAt().toString() : "");
            return m;
        }).toList();

        auditService.record("VIEWED_USERS", "SUCCESS", "User", null, "{}", request);

        return ResponseEntity.ok(Map.<String, Object>of(
            "content", content, "page", page, "size", size,
            "totalElements", total,
            "totalPages", (int) Math.ceil((double) total / size),
            "hasNext", end < total
        ));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Object> getUser(@PathVariable UUID id, HttpServletRequest request) {
        return userRepository.findById(id).map(u -> {
            auditService.record("VIEWED_USER", "SUCCESS", "User", id.toString(), "{}", request);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.getId());
            m.put("email", u.getEmail());
            m.put("displayName", u.getDisplayName());
            m.put("phone", u.getPhone());
            m.put("role", u.getRole() != null ? u.getRole().name() : "USER");
            m.put("status", u.getAccountStatus() != null ? u.getAccountStatus().name() : "ACTIVE");
            m.put("tenantId", u.getTenant() != null ? u.getTenant().getId() : null);
            m.put("tenantName", u.getTenant() != null ? u.getTenant().getBusinessName() : "");
            m.put("createdAt", u.getCreatedAt() != null ? u.getCreatedAt().toString() : "");
            return ResponseEntity.ok((Object) m);
        }).orElse(ResponseEntity.notFound().build());
    }

    @Transactional
    @PostMapping("/{id}/disable")
    public ResponseEntity<Map<String, Object>> disableUser(@PathVariable UUID id, HttpServletRequest request) {
        return userRepository.findById(id).map(u -> {
            u.setAccountStatus(com.chatcrmlite.backend.models.User.AccountStatus.LOCKED);
            userRepository.save(u);
            auditService.record("DISABLED_USER", "SUCCESS", "User", id.toString(), "{}", request);
            return ResponseEntity.ok(Map.<String, Object>of("message", "User disabled", "userId", id));
        }).orElse(ResponseEntity.notFound().build());
    }

    @Transactional
    @PostMapping("/{id}/enable")
    public ResponseEntity<Map<String, Object>> enableUser(@PathVariable UUID id, HttpServletRequest request) {
        return userRepository.findById(id).map(u -> {
            u.setAccountStatus(com.chatcrmlite.backend.models.User.AccountStatus.ACTIVE);
            userRepository.save(u);
            auditService.record("ENABLED_USER", "SUCCESS", "User", id.toString(), "{}", request);
            return ResponseEntity.ok(Map.<String, Object>of("message", "User enabled", "userId", id));
        }).orElse(ResponseEntity.notFound().build());
    }

    private boolean contains(String value, String search) {
        return value != null && value.toLowerCase().contains(search.toLowerCase());
    }
}
