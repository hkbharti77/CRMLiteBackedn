package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.FlowTemplateEngine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;

/**
 * Returns flow configuration for the authenticated tenant,
 * including the fixed (non-editable) trigger button/list labels
 * that are automatically determined by their business sub-category.
 *
 * Also exposes cache management endpoints so updated flow JSON files
 * can be picked up at runtime without a server restart.
 */
@RestController
@RequestMapping("/api/v1/flow-config")
public class FlowConfigController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FlowTemplateEngine templateEngine;

    // ════════════════════════════════════════════════════════════════════════
    //  Trigger Label Config
    // ════════════════════════════════════════════════════════════════════════

    /**
     * GET /api/v1/flow-config/trigger-labels
     *
     * Returns:
     * {
     *   "subCategory"       : "Premium Salons & Hair Clinics",
     *   "triggerButtonLabel": "✂️ Book Service",
     *   "triggerListLabel"  : "✂️ Book Service",
     *   "servicesLabel"     : "📋 Service Menu"
     * }
     */
    @GetMapping("/trigger-labels")
    public ResponseEntity<Map<String, String>> getTriggerLabels(
            @AuthenticationPrincipal String email) {

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        String subCategory = user.getBusinessSubType();

        return ResponseEntity.ok(Map.of(
                "subCategory",        subCategory != null ? subCategory : "",
                "triggerButtonLabel", templateEngine.getTriggerButtonLabel(user),
                "triggerListLabel",   templateEngine.getTriggerListLabel(user),
                "servicesLabel",      templateEngine.getServicesLabel(user)
        ));
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Cache Management — reload flow JSON without server restart
    // ════════════════════════════════════════════════════════════════════════

    /**
     * POST /api/v1/flow-config/cache/reload
     *
     * Clears ALL cached blueprints and labels so updated flow JSON files
     * are picked up on the next incoming WhatsApp message.
     *
     * Response:
     * { "status": "ok", "message": "All flow blueprints and label caches cleared." }
     */
    @PostMapping("/cache/reload")
    public ResponseEntity<Map<String, String>> reloadAllCaches(
            @AuthenticationPrincipal String email) {

        // Only allow authenticated users (admin check can be added here if needed)
        userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        templateEngine.clearCache();

        return ResponseEntity.ok(Map.of(
                "status",  "ok",
                "message", "All flow blueprints and label caches cleared. Changes will take effect on the next message."
        ));
    }

    /**
     * POST /api/v1/flow-config/cache/evict?subCategory=Dental+Clinics
     *
     * Evicts a single blueprint from cache by sub-category name.
     * More targeted than /reload — only the specified category reloads.
     *
     * Response:
     * { "status": "ok", "evicted": "dental-clinics" }
     */
    @PostMapping("/cache/evict")
    public ResponseEntity<Map<String, String>> evictBlueprint(
            @RequestParam("subCategory") String subCategory,
            @AuthenticationPrincipal String email) {

        userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        templateEngine.evictBlueprint(subCategory);

        return ResponseEntity.ok(Map.of(
                "status",      "ok",
                "subCategory", subCategory,
                "message",     "Blueprint cache evicted for '" + subCategory + "'. Will reload on next message."
        ));
    }

    /**
     * GET /api/v1/flow-config/cache/status
     *
     * Returns the list of currently cached blueprint slugs — useful for debugging.
     *
     * Response:
     * { "cachedSlugs": ["dental-clinics", "premium-salons-hair-clinics", ...] }
     */
    @GetMapping("/cache/status")
    public ResponseEntity<Map<String, Object>> getCacheStatus(
            @AuthenticationPrincipal String email) {

        userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        Set<String> slugs = templateEngine.getCachedSlugs();

        return ResponseEntity.ok(Map.of(
                "cachedCount", slugs.size(),
                "cachedSlugs", slugs
        ));
    }
}
