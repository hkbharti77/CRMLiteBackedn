package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.models.sms.SmsTemplate;
import com.chatcrmlite.backend.services.sms.SmsTemplateService;
import com.chatcrmlite.backend.utils.SmsSegmentCalculator;
import com.chatcrmlite.backend.utils.TenantResolver;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/sms/templates")
@RequiredArgsConstructor
public class SmsTemplateController {

    private final SmsTemplateService templateService;
    private final TenantResolver tenantResolver;

    @Data
    @Builder
    public static class TemplatePreviewResponse {
        private String renderedText;
        private String encoding;
        private int charCount;
        private int segments;
        private Set<String> detectedVariables;
    }

    @GetMapping
    public ResponseEntity<List<SmsTemplate>> getTemplates(
            @RequestHeader(name = "X-Tenant-ID", required = false) String xTenantId,
            @RequestParam(name = "businessId", required = false) String businessIdParam) {
        String businessId = tenantResolver.resolveBusinessId(xTenantId, businessIdParam);
        return ResponseEntity.ok(templateService.getTemplatesByBusiness(businessId));
    }

    @PostMapping
    public ResponseEntity<SmsTemplate> saveTemplate(
            @RequestHeader(name = "X-Tenant-ID", required = false) String xTenantId,
            @RequestParam(name = "businessId", required = false) String businessIdParam,
            @RequestBody SmsTemplate template) {
        String businessId = tenantResolver.resolveBusinessId(xTenantId, businessIdParam);
        return ResponseEntity.ok(templateService.saveTemplate(businessId, template));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTemplate(
            @PathVariable("id") UUID id,
            @RequestHeader(name = "X-Tenant-ID", required = false) String xTenantId,
            @RequestParam(name = "businessId", required = false) String businessIdParam) {
        String businessId = tenantResolver.resolveBusinessId(xTenantId, businessIdParam);
        templateService.deleteTemplate(id, businessId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/preview")
    public ResponseEntity<TemplatePreviewResponse> previewTemplate(@RequestBody Map<String, Object> body) {
        String content = (String) body.getOrDefault("content", "");
        Map<String, String> variables = (Map<String, String>) body.getOrDefault("variables", Map.of());

        String rendered = templateService.renderTemplate(content, variables);
        SmsSegmentCalculator.SegmentInfo segInfo = SmsSegmentCalculator.calculateSegments(rendered);
        Set<String> detectedVars = templateService.extractVariables(content);

        return ResponseEntity.ok(TemplatePreviewResponse.builder()
                .renderedText(rendered)
                .encoding(segInfo.getEncoding())
                .charCount(segInfo.getCharCount())
                .segments(segInfo.getSegments())
                .detectedVariables(detectedVars)
                .build());
    }
}
