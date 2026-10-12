package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.models.sms.SmsCampaign;
import com.chatcrmlite.backend.services.sms.SmsCampaignService;
import com.chatcrmlite.backend.utils.TenantResolver;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/sms/campaigns")
@com.chatcrmlite.backend.security.RequiresPage("PAGE_BROADCASTS")
@PreAuthorize("@perm.has(authentication, 'MODULE_CAMPAIGNS')")
@RequiredArgsConstructor
public class SmsCampaignController {

    private final SmsCampaignService campaignService;
    private final TenantResolver tenantResolver;

    @Data
    public static class CreateCampaignRequest {
        private String name;
        private UUID templateId;
        private String providerId;
        private List<String> phoneNumbers;
    }

    @GetMapping
    public ResponseEntity<List<SmsCampaign>> getCampaigns(
            @RequestHeader(name = "X-Tenant-ID", required = false) String xTenantId,
            @RequestParam(name = "businessId", required = false) String businessIdParam) {
        String businessId = tenantResolver.resolveBusinessId(xTenantId, businessIdParam);
        return ResponseEntity.ok(campaignService.getCampaignsByBusiness(businessId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<SmsCampaign> getCampaign(
            @PathVariable("id") UUID id,
            @RequestHeader(name = "X-Tenant-ID", required = false) String xTenantId,
            @RequestParam(name = "businessId", required = false) String businessIdParam) {
        String businessId = tenantResolver.resolveBusinessId(xTenantId, businessIdParam);
        return campaignService.getCampaignById(id, businessId)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Campaign not found"));
    }

    @PostMapping
    public ResponseEntity<SmsCampaign> createCampaign(
            @RequestHeader(name = "X-Tenant-ID", required = false) String xTenantId,
            @RequestParam(name = "businessId", required = false) String businessIdParam,
            @RequestBody CreateCampaignRequest request) {
        String businessId = tenantResolver.resolveBusinessId(xTenantId, businessIdParam);
        SmsCampaign campaign = new SmsCampaign();
        campaign.setName(request.getName());
        campaign.setTemplateId(request.getTemplateId());
        campaign.setProviderId(request.getProviderId());

        SmsCampaign created = campaignService.createCampaign(businessId, campaign, request.getPhoneNumbers());
        return ResponseEntity.ok(created);
    }

    @PostMapping("/{id}/trigger")
    public ResponseEntity<String> triggerCampaign(
            @PathVariable("id") UUID id,
            @RequestHeader(name = "X-Tenant-ID", required = false) String xTenantId,
            @RequestParam(name = "businessId", required = false) String businessIdParam) {
        String businessId = tenantResolver.resolveBusinessId(xTenantId, businessIdParam);

        // IDOR Check: Ensure campaign belongs to authenticated tenant before triggering
        if (campaignService.getCampaignById(id, businessId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Campaign not found or access denied");
        }

        campaignService.executeCampaignAsync(id, businessId);
        return ResponseEntity.ok("CAMPAIGN_DISPATCH_STARTED");
    }
}
