package com.chatcrmlite.backend.services.whatsapp.campaign;

import com.chatcrmlite.backend.clients.WhatsAppClient;
import com.chatcrmlite.backend.models.*;
import com.chatcrmlite.backend.repositories.*;
import com.chatcrmlite.backend.services.DistributedSchedulerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppCampaignService {

    private final WhatsAppCampaignRepository campaignRepository;
    private final WhatsAppTemplateRepository templateRepository;
    private final WhatsAppTemplateSnapshotRepository templateSnapshotRepository;
    private final WhatsAppConfigRepository whatsAppConfigRepository;
    private final WhatsAppClient whatsappClient;
    private final CampaignAudienceResolver audienceResolver;
    private final CampaignQueueProducer queueProducer;
    private final CampaignAnalyticsService analyticsService;
    private final CampaignAuditService auditService;
    private final WhatsAppCampaignRecipientRepository recipientRepository;
    private final PersonalizationEngine personalizationEngine;
    private final DistributedSchedulerService distributedSchedulerService;

    /**
     * Creates or updates a template snapshot ensuring immutable version history.
     */
    @Transactional
    public WhatsAppTemplateSnapshot createTemplateSnapshot(WhatsAppTemplate template) {
        Optional<WhatsAppTemplateSnapshot> latestSnapshot = templateSnapshotRepository
                .findFirstByOriginalTemplateIdOrderByVersionDesc(template.getId());

        int nextVersion = latestSnapshot.map(s -> s.getVersion() + 1).orElse(1);

        WhatsAppTemplateSnapshot snapshot = WhatsAppTemplateSnapshot.builder()
                .originalTemplateId(template.getId())
                .name(template.getName())
                .language(template.getLanguage())
                .category(template.getCategory())
                .status(template.getStatus())
                .version(nextVersion)
                .headerType(template.getHeaderType())
                .headerContent(template.getHeaderContent())
                .bodyText(template.getBodyText())
                .footerText(template.getFooterText())
                .buttonsJson(template.getButtonsJson())
                .metaTemplateId(template.getMetaTemplateId())
                .build();

        snapshot.setTenant(template.getTenant());
        return templateSnapshotRepository.save(snapshot);
    }

    /**
     * Create a new draft WhatsApp campaign.
     */
    @Transactional
    public WhatsAppCampaign createCampaign(String name, String templateIdOrName, WhatsAppCampaign.TargetType targetType,
                                            String targetFilterJson, String variableMappingJson, User owner) {

        if (templateIdOrName == null || templateIdOrName.trim().isEmpty()) {
            throw new IllegalArgumentException("Template ID or template name must be provided");
        }

        WhatsAppTemplate template = null;

        // 1. Try parsing as UUID if standard 36-char format
        if (templateIdOrName.trim().length() == 36 && templateIdOrName.contains("-")) {
            try {
                UUID uuid = UUID.fromString(templateIdOrName.trim());
                template = templateRepository.findById(uuid).orElse(null);
            } catch (IllegalArgumentException ignored) {
            }
        }

        // 2. If not found by UUID, try lookup by name or metaTemplateId
        if (template == null) {
            String cleanName = templateIdOrName.trim();
            UUID tenantId = (owner.getTenant() != null) ? owner.getTenant().getId() : null;

            if (tenantId != null) {
                template = templateRepository.findByNameAndTenantId(cleanName, tenantId).orElse(null);
            }
            if (template == null) {
                template = templateRepository.findByNameAndOwner(cleanName, owner).orElse(null);
            }
            if (template == null) {
                template = templateRepository.findFirstByName(cleanName).orElse(null);
            }
            if (template == null) {
                template = templateRepository.findFirstByMetaTemplateId(cleanName).orElse(null);
            }
            if (template == null) {
                throw new IllegalArgumentException("WhatsAppTemplate not found with ID or Name: " + cleanName);
            }
        }

        // Create immutable snapshot of template
        WhatsAppTemplateSnapshot snapshot = createTemplateSnapshot(template);

        // Validate variable mappings against template body
        if (!personalizationEngine.validateMapping(snapshot.getBodyText(), variableMappingJson)) {
            log.warn("[WhatsAppCampaignService] Variable mapping validation warning for template name={}", template.getName());
        }

        WhatsAppCampaign campaign = WhatsAppCampaign.builder()
                .name(name)
                .templateSnapshot(snapshot)
                .status(WhatsAppCampaign.Status.DRAFT)
                .targetType(targetType != null ? targetType : WhatsAppCampaign.TargetType.ALL_CONTACTS)
                .targetFilterJson(targetFilterJson)
                .variableMappingJson(variableMappingJson)
                .owner(owner)
                .build();

        campaign.setTenant(owner.getTenant());
        WhatsAppCampaign saved = campaignRepository.save(campaign);

        // Initialize Analytics record
        analyticsService.updateAnalyticsRollup(saved);
        auditService.logAction(saved, owner, WhatsAppCampaignAuditLog.Action.CAMPAIGN_CREATED, "{\"name\": \"" + name + "\"}");

        return saved;
    }

    /**
     * Dry Run / Test Send: Sends a rendered preview message to a test phone number.
     */
    @Transactional
    public String executeDryRun(UUID campaignId, String testPhoneNumber, User actor) {
        WhatsAppCampaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new IllegalArgumentException("Campaign not found"));

        WhatsAppConfig config = whatsAppConfigRepository.findByUserId(actor.getId())
                .orElseThrow(() -> new IllegalStateException("WhatsApp configuration not found for user"));

        WhatsAppTemplateSnapshot snapshot = campaign.getTemplateSnapshot();
        List<String> renderedParams = personalizationEngine.renderTemplateParameters(
                campaign.getVariableMappingJson(),
                null,
                null,
                actor
        );

        String renderedBody = snapshot.getBodyText();
        for (int i = 0; i < renderedParams.size(); i++) {
            renderedBody = renderedBody.replace("{{" + (i + 1) + "}}", renderedParams.get(i));
        }

        String waMessageId = whatsappClient.sendMessage(
                testPhoneNumber,
                "[TEST BROADCAST PREVIEW]\n" + renderedBody,
                config.getAccessToken(),
                config.getPhoneNumberId()
        );

        auditService.logAction(campaign, actor, WhatsAppCampaignAuditLog.Action.TEST_SENT, "{\"testPhone\": \"" + testPhoneNumber + "\"}");
        return waMessageId;
    }

    /**
     * Validates audience, resolves recipients snapshot, and queues or schedules campaign for execution.
     */
    @Transactional
    public WhatsAppCampaign scheduleOrExecuteCampaign(UUID campaignId, LocalDateTime scheduleTime, User actor) {
        WhatsAppCampaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new IllegalArgumentException("Campaign not found"));

        if (campaign.getStatus() == WhatsAppCampaign.Status.RUNNING || campaign.getStatus() == WhatsAppCampaign.Status.COMPLETED) {
            throw new IllegalStateException("Campaign is already in status: " + campaign.getStatus());
        }

        campaign.setStatus(WhatsAppCampaign.Status.VALIDATING);
        campaignRepository.save(campaign);

        // 1. Resolve and freeze immutable audience snapshot
        audienceResolver.resolveAndFreezeAudience(campaign);

        // 2. Queue recipients in Redis
        queueProducer.queueCampaignRecipients(campaign);

        // 3. Schedule or immediate execute
        if (scheduleTime != null && scheduleTime.isAfter(LocalDateTime.now())) {
            campaign.setScheduledAt(scheduleTime);
            campaign.setStatus(WhatsAppCampaign.Status.SCHEDULED);
            WhatsAppCampaign saved = campaignRepository.save(campaign);

            String tenantTz = (actor.getTenant() != null && actor.getTenant().getTimezone() != null)
                    ? actor.getTenant().getTimezone()
                    : "Asia/Kolkata";
            ZoneId zoneId;
            try {
                zoneId = ZoneId.of(tenantTz);
            } catch (Exception e) {
                zoneId = ZoneId.of("Asia/Kolkata");
            }

            Date startDate = Date.from(scheduleTime.atZone(zoneId).toInstant());
            distributedSchedulerService.scheduleOneTimeJob(
                    "campaign-job-" + saved.getId(),
                    com.chatcrmlite.backend.services.whatsapp.campaign.WhatsAppCampaignJob.class,
                    startDate,
                    Map.of("campaignId", saved.getId().toString())
            );

            auditService.logAction(saved, actor, WhatsAppCampaignAuditLog.Action.SCHEDULED, "{\"scheduledAt\": \"" + scheduleTime + "\"}");
            return saved;
        } else {
            campaign.setStatus(WhatsAppCampaign.Status.RUNNING);
            campaign.setStartedAt(LocalDateTime.now());
            WhatsAppCampaign saved = campaignRepository.save(campaign);

            auditService.logAction(saved, actor, WhatsAppCampaignAuditLog.Action.STARTED, "{\"startedAt\": \"" + LocalDateTime.now() + "\"}");
            return saved;
        }
    }

    @Transactional
    public WhatsAppCampaign pauseCampaign(UUID campaignId, User actor) {
        WhatsAppCampaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new IllegalArgumentException("Campaign not found"));
        campaign.setStatus(WhatsAppCampaign.Status.PAUSED);
        WhatsAppCampaign saved = campaignRepository.save(campaign);
        auditService.logAction(saved, actor, WhatsAppCampaignAuditLog.Action.PAUSED, "{}");
        return saved;
    }

    @Transactional
    public WhatsAppCampaign resumeCampaign(UUID campaignId, User actor) {
        WhatsAppCampaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new IllegalArgumentException("Campaign not found"));
        campaign.setStatus(WhatsAppCampaign.Status.RUNNING);
        WhatsAppCampaign saved = campaignRepository.save(campaign);
        auditService.logAction(saved, actor, WhatsAppCampaignAuditLog.Action.RESUMED, "{}");
        return saved;
    }

    @Transactional
    public WhatsAppCampaign cancelCampaign(UUID campaignId, User actor) {
        WhatsAppCampaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new IllegalArgumentException("Campaign not found"));
        campaign.setStatus(WhatsAppCampaign.Status.CANCELLED);
        WhatsAppCampaign saved = campaignRepository.save(campaign);
        auditService.logAction(saved, actor, WhatsAppCampaignAuditLog.Action.CANCELLED, "{}");
        return saved;
    }

    @Transactional(readOnly = true)
    public Page<WhatsAppCampaign> getCampaigns(User owner, Pageable pageable) {
        return campaignRepository.findByOwner(owner, pageable);
    }

    @Transactional(readOnly = true)
    public WhatsAppCampaign getCampaign(UUID id) {
        return campaignRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Campaign not found"));
    }

    public WhatsAppCampaignAnalytics getAnalytics(UUID campaignId) {
        WhatsAppCampaign campaign = getCampaign(campaignId);
        return analyticsService.updateAnalyticsRollup(campaign);
    }

    public List<WhatsAppCampaignAuditLog> getAuditLogs(UUID campaignId) {
        WhatsAppCampaign campaign = getCampaign(campaignId);
        return auditService.getAuditLogs(campaign);
    }

    public Page<WhatsAppCampaignRecipient> getRecipients(UUID campaignId, Pageable pageable) {
        WhatsAppCampaign campaign = getCampaign(campaignId);
        return recipientRepository.findByCampaign(campaign, pageable);
    }
}
