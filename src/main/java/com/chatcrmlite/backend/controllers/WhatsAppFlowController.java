package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.flows.*;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.repositories.flows.FlowRevisionRepository;
import com.chatcrmlite.backend.repositories.flows.FlowSubmissionRepository;
import com.chatcrmlite.backend.repositories.flows.WhatsAppFlowAuditLogRepository;
import com.chatcrmlite.backend.services.whatsapp.flows.WhatsAppFlowService;
import com.chatcrmlite.backend.dto.flow.FlowFieldConfig;
import com.chatcrmlite.backend.services.FlowConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/whatsapp-flows")
public class WhatsAppFlowController {

    private final WhatsAppFlowService flowService;
    private final FlowRevisionRepository revisionRepository;
    private final FlowSubmissionRepository submissionRepository;
    private final WhatsAppFlowAuditLogRepository auditLogRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final FlowConfigService flowConfigService;

    public WhatsAppFlowController(WhatsAppFlowService flowService,
                                  FlowRevisionRepository revisionRepository,
                                  FlowSubmissionRepository submissionRepository,
                                  WhatsAppFlowAuditLogRepository auditLogRepository,
                                  UserRepository userRepository,
                                  ObjectMapper objectMapper,
                                  FlowConfigService flowConfigService) {
        this.flowService = flowService;
        this.revisionRepository = revisionRepository;
        this.submissionRepository = submissionRepository;
        this.auditLogRepository = auditLogRepository;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
        this.flowConfigService = flowConfigService;
    }

    private User resolveAuthenticatedUser(Object principal) {
        String email = null;
        if (principal instanceof UserDetails ud) {
            email = ud.getUsername();
        } else if (principal instanceof String s && !s.isBlank()) {
            email = s;
        } else if (principal instanceof Principal p) {
            email = p.getName();
        }

        if (email == null || email.isBlank() || "anonymousUser".equals(email)) {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
                Object authPrincipal = auth.getPrincipal();
                if (authPrincipal instanceof UserDetails ud) {
                    email = ud.getUsername();
                } else if (authPrincipal instanceof String s) {
                    email = s;
                } else if (authPrincipal instanceof Principal p) {
                    email = p.getName();
                }
            }
        }

        if (email == null || email.isBlank() || "anonymousUser".equals(email)) {
            throw new IllegalStateException("Authentication is required");
        }

        final String userEmail = email;
        return userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found for email: " + userEmail));
    }

    @GetMapping
    public ResponseEntity<List<WhatsAppFlow>> listFlows(@AuthenticationPrincipal Object principal) {
        User user = resolveAuthenticatedUser(principal);
        return ResponseEntity.ok(flowService.listTenantFlows(user));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getFlow(@PathVariable("id") UUID id, @AuthenticationPrincipal Object principal) {
        User user = resolveAuthenticatedUser(principal);
        WhatsAppFlow flow = flowService.getFlow(id, user);
        List<FlowRevision> revisions = revisionRepository.findAllByFlowIdOrderByVersionDesc(flow.getId());

        Map<String, Object> response = new HashMap<>();
        response.put("flow", flow);
        response.put("revisions", revisions);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/templates")
    public ResponseEntity<List<Map<String, Object>>> getTemplates(@AuthenticationPrincipal Object principal) {
        User user = null;
        try {
            user = resolveAuthenticatedUser(principal);
        } catch (Exception ignored) {}
        return ResponseEntity.ok(flowService.getPrebuiltTemplates(user));
    }

    @GetMapping("/master-fields")
    public ResponseEntity<List<FlowFieldConfig>> getMasterFields(
            @RequestParam(required = false) String category,
            @AuthenticationPrincipal Object principal) {
        User user = resolveAuthenticatedUser(principal);
        String cat = category != null ? category.toLowerCase() : "lead";
        String flowType;
        if (cat.contains("appt") || cat.contains("appointment")) flowType = "appointment";
        else if (cat.contains("book")) flowType = "booking";
        else if (cat.contains("supp") || cat.contains("ticket")) flowType = "support";
        else if (cat.contains("feed") || cat.contains("survey")) flowType = "feedback";
        else flowType = "lead";

        return ResponseEntity.ok(flowConfigService.getConfigurableFields(user, flowType));
    }

    @PostMapping("/draft")
    public ResponseEntity<WhatsAppFlow> createDraft(@RequestBody Map<String, Object> payload, @AuthenticationPrincipal Object principal) {
        User user = resolveAuthenticatedUser(principal);
        String name = (String) payload.get("name");
        String categoryStr = (String) payload.get("category");
        FlowCategory category = (categoryStr != null && !categoryStr.isBlank()) ? FlowCategory.valueOf(categoryStr) : FlowCategory.LEAD_GENERATION;
        
        Object fieldsObj = payload.get("fieldsConfig");
        String fieldsConfigJson;
        try {
            fieldsConfigJson = (fieldsObj instanceof String s) ? s : objectMapper.writeValueAsString(fieldsObj);
        } catch (Exception e) {
            fieldsConfigJson = "[]";
        }
        String confirmationMessage = (String) payload.get("confirmationMessage");

        WhatsAppFlow flow = flowService.saveDraft(name, category, fieldsConfigJson, confirmationMessage, user);
        return ResponseEntity.ok(flow);
    }

    @PutMapping("/{id}/draft")
    public ResponseEntity<WhatsAppFlow> updateDraft(@PathVariable("id") UUID id, @RequestBody Map<String, Object> payload, @AuthenticationPrincipal Object principal) {
        User user = resolveAuthenticatedUser(principal);
        String name = (String) payload.get("name");
        String categoryStr = (String) payload.get("category");
        FlowCategory category = (categoryStr != null && !categoryStr.isBlank()) ? FlowCategory.valueOf(categoryStr) : null;

        Object fieldsObj = payload.get("fieldsConfig");
        String fieldsConfigJson;
        try {
            fieldsConfigJson = (fieldsObj instanceof String s) ? s : objectMapper.writeValueAsString(fieldsObj);
        } catch (Exception e) {
            fieldsConfigJson = "[]";
        }
        String confirmationMessage = (String) payload.get("confirmationMessage");

        WhatsAppFlow flow = flowService.updateDraftRevision(id, name, category, fieldsConfigJson, confirmationMessage, user);
        return ResponseEntity.ok(flow);
    }

    @PostMapping("/{id}/publish")
    public ResponseEntity<Map<String, Object>> queuePublish(@PathVariable("id") UUID id, @AuthenticationPrincipal Object principal) {
        User user = resolveAuthenticatedUser(principal);
        FlowPublishJob job = flowService.queuePublishFlow(id, user);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("jobId", job.getId().toString());
        response.put("status", "PUBLISHING");
        response.put("message", "Flow publish has been queued in background.");
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/duplicate")
    public ResponseEntity<WhatsAppFlow> duplicateFlow(@PathVariable("id") UUID id, @AuthenticationPrincipal Object principal) {
        User user = resolveAuthenticatedUser(principal);
        WhatsAppFlow cloned = flowService.duplicateFlow(id, user);
        return ResponseEntity.ok(cloned);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> archiveFlow(@PathVariable("id") UUID id, @AuthenticationPrincipal Object principal) {
        User user = resolveAuthenticatedUser(principal);
        flowService.archiveFlow(id, user);
        return ResponseEntity.ok(Map.of("success", true, "message", "Flow archived successfully."));
    }

    @GetMapping("/{id}/audit-logs")
    public ResponseEntity<List<WhatsAppFlowAuditLog>> getAuditLogs(@PathVariable("id") UUID id, @AuthenticationPrincipal Object principal) {
        // P3-03-02: Enforce tenant ownership before querying audit logs.
        // flowService.getFlow() internally calls flowRepository.findByIdAndTenantId(id, tenantId)
        // and throws NoSuchElementException when the flow belongs to another tenant or does not
        // exist. We translate that to 404 so the auditLogRepository is NEVER queried for an
        // unauthorized flow, preventing IDOR/BOLA.
        User user = resolveAuthenticatedUser(principal);
        WhatsAppFlow flow;
        try {
            flow = flowService.getFlow(id, user);
        } catch (java.util.NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(auditLogRepository.findAllByFlowIdOrderByCreatedAtDesc(flow.getId()));
    }

    @PostMapping("/sync-meta")
    public ResponseEntity<Map<String, Object>> syncFlowsFromMeta(@AuthenticationPrincipal Object principal) {
        User user = resolveAuthenticatedUser(principal);
        Map<String, Object> result = flowService.syncFlowsFromMeta(user);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}/submissions")
    public ResponseEntity<List<FlowSubmission>> getSubmissions(@PathVariable("id") UUID id, @AuthenticationPrincipal Object principal) {
        // P3-03-01: Enforce tenant ownership before querying submissions.
        // flowService.getFlow() internally calls flowRepository.findByIdAndTenantId(id, tenantId)
        // and throws NoSuchElementException when the flow belongs to another tenant or does not
        // exist. We translate that to 404 so the submissionRepository is NEVER queried for an
        // unauthorized flow, and the caller cannot distinguish "wrong tenant" from "not found".
        User user = resolveAuthenticatedUser(principal);
        WhatsAppFlow flow;
        try {
            flow = flowService.getFlow(id, user);
        } catch (java.util.NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(submissionRepository.findAllByFlowIdOrderByCreatedAtDesc(flow.getId()));
    }
}
