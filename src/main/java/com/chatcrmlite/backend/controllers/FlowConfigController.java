package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.FlowTemplateEngine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Returns flow configuration for the authenticated tenant,
 * including the fixed (non-editable) trigger button/list labels
 * that are automatically determined by their business sub-category.
 */
@RestController
@RequestMapping("/api/v1/flow-config")
public class FlowConfigController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FlowTemplateEngine templateEngine;

    /**
     * GET /api/v1/flow-config/trigger-labels
     *
     * Returns:
     * {
     *   "subCategory"       : "Premium Salons & Hair Clinics",
     *   "triggerButtonLabel": "💇 Book My Slot",   // fixed, non-editable button (mode=button)
     *   "triggerListLabel"  : "💇 Book Salon Slot"  // fixed, non-editable list option (mode=list)
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
                "triggerButtonLabel", templateEngine.getTriggerButtonLabel(subCategory),
                "triggerListLabel",   templateEngine.getTriggerListLabel(subCategory),
                "servicesLabel",      templateEngine.getServicesLabel(subCategory)
        ));
    }
}
