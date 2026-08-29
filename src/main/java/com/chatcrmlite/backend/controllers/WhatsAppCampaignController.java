package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.dto.BroadcastCsvUploadResultDTO;
import com.chatcrmlite.backend.dto.BroadcastFilterConfigDTO;
import com.chatcrmlite.backend.dtos.CreateCampaignRequestDto;
import com.chatcrmlite.backend.dtos.DryRunRequestDto;
import com.chatcrmlite.backend.dtos.ScheduleCampaignRequestDto;
import com.chatcrmlite.backend.models.BroadcastUploadFilterConfig;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.WhatsAppCampaign;
import com.chatcrmlite.backend.models.WhatsAppCampaignAnalytics;
import com.chatcrmlite.backend.models.WhatsAppCampaignAuditLog;
import com.chatcrmlite.backend.repositories.BroadcastUploadFilterConfigRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.whatsapp.campaign.BroadcastCsvParserService;
import com.chatcrmlite.backend.services.whatsapp.campaign.WhatsAppCampaignService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/v1/whatsapp/campaigns")
@com.chatcrmlite.backend.security.RequiresPage("PAGE_BROADCASTS")
@PreAuthorize("@perm.has(authentication, 'MODULE_CAMPAIGNS')")
@RequiredArgsConstructor
public class WhatsAppCampaignController {

    private final WhatsAppCampaignService campaignService;
    private final UserRepository userRepository;
    private final BroadcastCsvParserService csvParserService;
    private final BroadcastUploadFilterConfigRepository filterConfigRepository;
    private final ObjectMapper objectMapper;

    private User getAuthenticatedUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Authenticated user not found"));
    }

    @PostMapping
    public ResponseEntity<WhatsAppCampaign> createCampaign(
            @RequestBody CreateCampaignRequestDto request,
            @AuthenticationPrincipal String email) {
        User user = getAuthenticatedUser(email);
        WhatsAppCampaign campaign = campaignService.createCampaign(
                request.getName(),
                request.getTemplateId(),
                request.getTargetType(),
                request.getTargetFilterJson(),
                request.getVariableMappingJson(),
                request.getSaveImportedRecipients(),
                request.getPriority(),
                user
        );
        return ResponseEntity.ok(campaign);
    }

    @GetMapping
    public ResponseEntity<Page<WhatsAppCampaign>> getCampaigns(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal String email) {
        User user = getAuthenticatedUser(email);
        return ResponseEntity.ok(campaignService.getCampaigns(user, PageRequest.of(page, size)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<WhatsAppCampaign> getCampaign(
            @PathVariable UUID id,
            @AuthenticationPrincipal String email) {
        User user = getAuthenticatedUser(email);
        return ResponseEntity.ok(campaignService.getCampaign(id, user));
    }

    @PostMapping("/{id}/dry-run")
    public ResponseEntity<Map<String, String>> executeDryRun(
            @PathVariable UUID id,
            @RequestBody DryRunRequestDto request,
            @AuthenticationPrincipal String email) {
        User user = getAuthenticatedUser(email);
        String waMessageId = campaignService.executeDryRun(id, request.getTestPhoneNumber(), user);
        return ResponseEntity.ok(Map.of("message", "Dry run sent successfully", "waMessageId", waMessageId));
    }

    @PostMapping("/{id}/schedule")
    public ResponseEntity<WhatsAppCampaign> scheduleOrExecuteCampaign(
            @PathVariable UUID id,
            @RequestBody(required = false) ScheduleCampaignRequestDto request,
            @AuthenticationPrincipal String email) {
        User user = getAuthenticatedUser(email);
        WhatsAppCampaign campaign = campaignService.scheduleOrExecuteCampaign(
                id,
                request != null ? request.getScheduleTime() : null,
                user
        );
        return ResponseEntity.ok(campaign);
    }

    @PostMapping("/{id}/pause")
    public ResponseEntity<WhatsAppCampaign> pauseCampaign(
            @PathVariable UUID id,
            @AuthenticationPrincipal String email) {
        User user = getAuthenticatedUser(email);
        return ResponseEntity.ok(campaignService.pauseCampaign(id, user));
    }

    @PostMapping("/{id}/resume")
    public ResponseEntity<WhatsAppCampaign> resumeCampaign(
            @PathVariable UUID id,
            @AuthenticationPrincipal String email) {
        User user = getAuthenticatedUser(email);
        return ResponseEntity.ok(campaignService.resumeCampaign(id, user));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<WhatsAppCampaign> cancelCampaign(
            @PathVariable UUID id,
            @AuthenticationPrincipal String email) {
        User user = getAuthenticatedUser(email);
        return ResponseEntity.ok(campaignService.cancelCampaign(id, user));
    }

    @GetMapping("/{id}/analytics")
    public ResponseEntity<WhatsAppCampaignAnalytics> getAnalytics(
            @PathVariable UUID id,
            @AuthenticationPrincipal String email) {
        User user = getAuthenticatedUser(email);
        return ResponseEntity.ok(campaignService.getAnalytics(id, user));
    }

    @GetMapping("/{id}/audit-logs")
    public ResponseEntity<List<WhatsAppCampaignAuditLog>> getAuditLogs(
            @PathVariable UUID id,
            @AuthenticationPrincipal String email) {
        User user = getAuthenticatedUser(email);
        return ResponseEntity.ok(campaignService.getAuditLogs(id, user));
    }

    @GetMapping("/{id}/recipients")
    public ResponseEntity<Page<com.chatcrmlite.backend.models.WhatsAppCampaignRecipient>> getRecipients(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @AuthenticationPrincipal String email) {
        User user = getAuthenticatedUser(email);
        return ResponseEntity.ok(campaignService.getRecipients(id, user, PageRequest.of(page, size)));
    }

    // ── CSV Upload Endpoint ──────────────────────────────────────────────────

    /**
     * Parses and validates a CSV/XLSX file for broadcast audience targeting.
     * Does NOT create a campaign — just returns parsed columns, validation stats, and sample rows.
     */
    @PostMapping(value = "/upload-csv", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<BroadcastCsvUploadResultDTO> uploadCsvForBroadcast(
            @RequestPart("file") MultipartFile file,
            @AuthenticationPrincipal String email) {
        getAuthenticatedUser(email); // verify auth
        BroadcastCsvUploadResultDTO result = csvParserService.parseAndValidate(file);
        return ResponseEntity.ok(result);
    }

    // ── Filter Config Endpoints ──────────────────────────────────────────────

    /**
     * Returns the admin-defined broadcast upload filter configuration for this tenant.
     */
    @GetMapping("/filter-config")
    public ResponseEntity<BroadcastFilterConfigDTO> getFilterConfig(
            @AuthenticationPrincipal String email) {
        User user = getAuthenticatedUser(email);
        UUID tenantId = user.getTenant() != null ? user.getTenant().getId() : null;

        if (tenantId == null) {
            return ResponseEntity.ok(BroadcastFilterConfigDTO.builder()
                    .filterColumns(Collections.emptyList())
                    .filterRules(Collections.emptyList())
                    .build());
        }

        return filterConfigRepository.findByTenantId(tenantId)
                .map(config -> {
                    List<String> columns = parseJsonList(config.getFilterColumnsJson());
                    List<BroadcastFilterConfigDTO.FilterRuleDTO> rules = parseFilterRules(config.getFilterRulesJson());
                    return ResponseEntity.ok(BroadcastFilterConfigDTO.builder()
                            .filterColumns(columns)
                            .filterRules(rules)
                            .build());
                })
                .orElse(ResponseEntity.ok(BroadcastFilterConfigDTO.builder()
                        .filterColumns(Collections.emptyList())
                        .filterRules(Collections.emptyList())
                        .build()));
    }

    /**
     * Updates the broadcast upload filter configuration. Restricted to OWNER and ADMIN roles.
     */
    @PutMapping("/filter-config")
    public ResponseEntity<BroadcastFilterConfigDTO> updateFilterConfig(
            @RequestBody BroadcastFilterConfigDTO dto,
            @AuthenticationPrincipal String email) {
        User user = getAuthenticatedUser(email);

        if (user.getRole() != User.Role.OWNER && user.getRole() != User.Role.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only OWNER or ADMIN users can update the broadcast filter config");
        }

        UUID tenantId = user.getTenant() != null ? user.getTenant().getId() : null;
        if (tenantId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "User has no tenant");
        }

        BroadcastUploadFilterConfig config = filterConfigRepository.findByTenantId(tenantId)
                .orElseGet(() -> {
                    BroadcastUploadFilterConfig c = new BroadcastUploadFilterConfig();
                    c.setTenantId(tenantId);
                    return c;
                });

        try {
            config.setFilterColumnsJson(
                    dto.getFilterColumns() != null ? objectMapper.writeValueAsString(dto.getFilterColumns()) : "[]");
            config.setFilterRulesJson(
                    dto.getFilterRules() != null ? objectMapper.writeValueAsString(dto.getFilterRules()) : "[]");
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid JSON in filter config");
        }

        filterConfigRepository.save(config);

        return ResponseEntity.ok(BroadcastFilterConfigDTO.builder()
                .filterColumns(dto.getFilterColumns() != null ? dto.getFilterColumns() : Collections.emptyList())
                .filterRules(dto.getFilterRules() != null ? dto.getFilterRules() : Collections.emptyList())
                .build());
    }

    // ── JSON Helpers ─────────────────────────────────────────────────────────

    private List<String> parseJsonList(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private List<BroadcastFilterConfigDTO.FilterRuleDTO> parseFilterRules(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, BroadcastFilterConfigDTO.FilterRuleDTO.class));
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
