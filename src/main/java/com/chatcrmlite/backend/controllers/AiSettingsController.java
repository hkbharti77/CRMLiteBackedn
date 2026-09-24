package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.TenantRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import jakarta.validation.constraints.Size;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import org.springframework.beans.factory.annotation.Value;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.output.Response;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dedicated AI Settings API — keeps AI configuration separate from
 * general tenant / profile settings.
 *
 * Endpoints:
 *   GET  /api/v1/settings/ai/persona  — fetch the current tenant's AI persona
 *   PUT  /api/v1/settings/ai/persona  — update the current tenant's AI persona
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/settings/ai")
@Transactional
public class AiSettingsController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TenantRepository tenantRepository;

    // ─── GET  /api/v1/settings/ai/persona ────────────────────────────────────
    @GetMapping("/persona")
    public ResponseEntity<Map<String, Object>> getPersona(@AuthenticationPrincipal String email) {

        if (email == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        Tenant tenant = user.getTenant();
        if (tenant == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        Map<String, Object> response = new HashMap<>();
        response.put("aiPersonaPrompt", tenant.getAiPersonaPrompt());
        response.put("aiEmailSentimentPrompt", tenant.getAiEmailSentimentPrompt());
        response.put("updatedAt", tenant.getAiPersonaUpdatedAt());
        response.put("updatedBy", tenant.getAiPersonaUpdatedBy());
        return ResponseEntity.ok(response);
    }

    // ─── PUT  /api/v1/settings/ai/persona ────────────────────────────────────
    @PutMapping("/persona")
    public ResponseEntity<Map<String, Object>> updatePersona(
            @AuthenticationPrincipal String email,
            @RequestBody Map<String, String> body) {

        if (email == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Security: Only OWNER, ADMIN, or SUPER_ADMIN may edit the AI persona
        if (user.getRole() != User.Role.OWNER && user.getRole() != User.Role.ADMIN && user.getRole() != User.Role.SUPER_ADMIN) {
            Map<String, Object> err = new HashMap<>();
            err.put("error", "Only owners or admins can modify AI persona settings.");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(err);
        }

        Tenant tenant = user.getTenant();
        if (tenant == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        String prompt = body.getOrDefault("aiPersonaPrompt", "");
        String sentimentPrompt = body.getOrDefault("aiEmailSentimentPrompt", "");

        // Validation: max 4000 characters
        if (prompt.length() > 4000 || sentimentPrompt.length() > 4000) {
            Map<String, Object> err = new HashMap<>();
            err.put("error", "Prompts must not exceed 4000 characters.");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err);
        }

        // Persist
        tenant.setAiPersonaPrompt(prompt.isBlank() ? null : prompt.trim());
        tenant.setAiEmailSentimentPrompt(sentimentPrompt.isBlank() ? null : sentimentPrompt.trim());
        tenant.setAiPersonaUpdatedAt(LocalDateTime.now());
        tenant.setAiPersonaUpdatedBy(user.getId());
        tenantRepository.save(tenant);

        log.info("[AiSettings] Persona updated for tenant {} by user {}",
                tenant.getId(), user.getEmail());

        Map<String, Object> response = new HashMap<>();
        response.put("message", "AI persona updated successfully.");
        response.put("aiPersonaPrompt", tenant.getAiPersonaPrompt());
        response.put("aiEmailSentimentPrompt", tenant.getAiEmailSentimentPrompt());
        response.put("updatedAt", tenant.getAiPersonaUpdatedAt());
        response.put("updatedBy", tenant.getAiPersonaUpdatedBy());
        return ResponseEntity.ok(response);
    }

    // ─── GET  /api/v1/settings/ai/voice-persona ──────────────────────────────
    @GetMapping("/voice-persona")
    public ResponseEntity<Map<String, Object>> getVoicePersona(@AuthenticationPrincipal String email) {
        if (email == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        Tenant tenant = user.getTenant();
        if (tenant == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        Map<String, Object> response = new HashMap<>();
        response.put("voicePersonaPrompt", tenant.getVoicePersonaPrompt());
        response.put("voiceAssistantName", tenant.getVoiceAssistantName());
        response.put("aiPersonaPrompt", tenant.getAiPersonaPrompt());
        return ResponseEntity.ok(response);
    }

    // ─── PUT  /api/v1/settings/ai/voice-persona ──────────────────────────────
    @PutMapping("/voice-persona")
    public ResponseEntity<Map<String, Object>> updateVoicePersona(
            @AuthenticationPrincipal String email,
            @RequestBody Map<String, String> body) {

        if (email == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (user.getRole() != User.Role.OWNER && user.getRole() != User.Role.ADMIN && user.getRole() != User.Role.SUPER_ADMIN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Only owners or admins can modify voice persona settings."));
        }

        Tenant tenant = user.getTenant();
        if (tenant == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        String voicePrompt = body.getOrDefault("voicePersonaPrompt", "");
        String assistantName = body.getOrDefault("voiceAssistantName", "Assistant");

        if (voicePrompt.length() > 4000) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Voice persona prompt must not exceed 4000 characters."));
        }

        tenant.setVoicePersonaPrompt(voicePrompt.isBlank() ? null : voicePrompt.trim());
        tenant.setVoiceAssistantName(assistantName.isBlank() ? "Assistant" : assistantName.trim());
        tenantRepository.save(tenant);

        log.info("[AiSettings] Voice persona updated for tenant {} by user {}", tenant.getId(), user.getEmail());

        Map<String, Object> response = new HashMap<>();
        response.put("message", "Voice persona updated successfully.");
        response.put("voicePersonaPrompt", tenant.getVoicePersonaPrompt());
        response.put("voiceAssistantName", tenant.getVoiceAssistantName());
        return ResponseEntity.ok(response);
    }

    // ─── GET  /api/v1/settings/ai/lead-emails ─────────────────────────────────
    /**
     * Returns this tenant's lead notification email templates.
     * Null values mean the system will use the built-in hardcoded defaults.
     */
    @GetMapping("/lead-emails")
    public ResponseEntity<Map<String, Object>> getLeadEmails(@AuthenticationPrincipal String email) {
        if (email == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
        Tenant tenant = user.getTenant();
        if (tenant == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();

        Map<String, Object> response = new HashMap<>();
        response.put("leadCustomerEmailSubject", tenant.getLeadCustomerEmailSubject());
        response.put("leadCustomerEmailBody",    tenant.getLeadCustomerEmailBody());
        response.put("availablePlaceholders",    java.util.List.of(
            "{{contactName}}", "{{businessName}}", "{{enquiryMessage}}", "{{contactEmail}}", "{{ownerName}}"
        ));
        return ResponseEntity.ok(response);
    }

    // ─── PUT  /api/v1/settings/ai/lead-emails ─────────────────────────────────
    /**
     * Allows OWNER or ADMIN to customise the lead customer notification email for their tenant.
     * Send null or blank to revert a field back to the system default.
     */
    @PutMapping("/lead-emails")
    public ResponseEntity<Map<String, Object>> updateLeadEmails(
            @AuthenticationPrincipal String email,
            @RequestBody Map<String, String> body) {

        if (email == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (user.getRole() != User.Role.OWNER && user.getRole() != User.Role.ADMIN && user.getRole() != User.Role.SUPER_ADMIN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Only owners or admins can modify lead email settings."));
        }

        Tenant tenant = user.getTenant();
        if (tenant == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();

        String custSubject = body.get("leadCustomerEmailSubject");
        String custBody    = body.get("leadCustomerEmailBody");

        tenant.setLeadCustomerEmailSubject(custSubject == null || custSubject.isBlank() ? null : custSubject.trim());
        tenant.setLeadCustomerEmailBody(   custBody    == null || custBody.isBlank()    ? null : custBody.trim());

        tenantRepository.save(tenant);
        log.info("[AiSettings] Customer lead email template updated for tenant {} by user {}", tenant.getId(), user.getEmail());

        Map<String, Object> response = new HashMap<>();
        response.put("message", "Lead customer email template updated successfully.");
        response.put("leadCustomerEmailSubject", tenant.getLeadCustomerEmailSubject());
        response.put("leadCustomerEmailBody",    tenant.getLeadCustomerEmailBody());
        return ResponseEntity.ok(response);
    }

    @Autowired(required = false)
    private ChatLanguageModel chatLanguageModel;

    @Value("${ai.provider:zai}")
    private String configuredProvider;

    // ─── GET  /api/v1/settings/ai/test-provider ─────────────────────────────
    /**
     * Test active AI provider (Bedrock, Zai, OpenRouter, etc.) connection and key validity.
     */
    @GetMapping("/test-provider")
    public ResponseEntity<Map<String, Object>> testAiProvider(@AuthenticationPrincipal String email) {
        if (email == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        Map<String, Object> res = new HashMap<>();
        res.put("configuredProvider", configuredProvider);

        if (chatLanguageModel == null) {
            res.put("status", "ERROR");
            res.put("message", "No active ChatLanguageModel bean configured. Check AI_PROVIDER and API Key in .env.");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(res);
        }

        try {
            Response<AiMessage> modelResponse = chatLanguageModel.generate(List.of(UserMessage.from("Hello! Test AI response.")));
            res.put("status", "SUCCESS");
            res.put("response", modelResponse.content() != null ? modelResponse.content().text() : "No response content");
            res.put("tokenUsage", modelResponse.tokenUsage() != null ? modelResponse.tokenUsage().toString() : "N/A");
            return ResponseEntity.ok(res);
        } catch (Exception e) {
            log.error("[AiSettings] AI Provider test error: {}", e.getMessage(), e);
            res.put("status", "FAILED");
            res.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(res);
        }
    }
}

