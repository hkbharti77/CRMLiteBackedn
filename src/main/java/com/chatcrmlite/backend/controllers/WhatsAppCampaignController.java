package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.dtos.CreateCampaignRequestDto;
import com.chatcrmlite.backend.dtos.DryRunRequestDto;
import com.chatcrmlite.backend.dtos.ScheduleCampaignRequestDto;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.WhatsAppCampaign;
import com.chatcrmlite.backend.models.WhatsAppCampaignAnalytics;
import com.chatcrmlite.backend.models.WhatsAppCampaignAuditLog;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.whatsapp.campaign.WhatsAppCampaignService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/whatsapp/campaigns")
@RequiredArgsConstructor
public class WhatsAppCampaignController {

    private final WhatsAppCampaignService campaignService;
    private final UserRepository userRepository;

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
    public ResponseEntity<WhatsAppCampaign> getCampaign(@PathVariable UUID id) {
        return ResponseEntity.ok(campaignService.getCampaign(id));
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
    public ResponseEntity<WhatsAppCampaignAnalytics> getAnalytics(@PathVariable UUID id) {
        return ResponseEntity.ok(campaignService.getAnalytics(id));
    }

    @GetMapping("/{id}/audit-logs")
    public ResponseEntity<List<WhatsAppCampaignAuditLog>> getAuditLogs(@PathVariable UUID id) {
        return ResponseEntity.ok(campaignService.getAuditLogs(id));
    }
}
