package com.chatcrmlite.backend.services.whatsapp.campaign;

import com.chatcrmlite.backend.dtos.CampaignAbMetricsDto;
import com.chatcrmlite.backend.dtos.CampaignVariantDto;
import com.chatcrmlite.backend.models.*;
import com.chatcrmlite.backend.repositories.*;
import com.chatcrmlite.backend.services.DistributedSchedulerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppAbTestingService {

    private final WhatsAppCampaignRepository campaignRepository;
    private final WhatsAppCampaignVariantRepository variantRepository;
    private final WhatsAppCampaignRecipientRepository recipientRepository;
    private final WhatsAppTemplateRepository templateRepository;
    private final WhatsAppTemplateSnapshotRepository templateSnapshotRepository;
    private final PersonalizationEngine personalizationEngine;
    private final LeadRepository leadRepository;
    private final CampaignQueueProducer queueProducer;
    private final DistributedSchedulerService distributedSchedulerService;
    private final CampaignAuditService auditService;
    private final ObjectMapper objectMapper;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * Resolves and creates template snapshots and variant entities for an A/B test campaign.
     */
    @Transactional
    public List<WhatsAppCampaignVariant> createVariantsForCampaign(WhatsAppCampaign campaign, List<CampaignVariantDto> variantDtos, User actor) {
        if (variantDtos == null || variantDtos.size() < 2) {
            throw new IllegalArgumentException("A/B test campaign requires at least 2 variants (e.g. Variant A and Variant B)");
        }

        List<WhatsAppCampaignVariant> savedVariants = new ArrayList<>();
        UUID tenantId = (campaign.getTenant() != null) ? campaign.getTenant().getId() : null;

        for (int i = 0; i < variantDtos.size(); i++) {
            CampaignVariantDto dto = variantDtos.get(i);
            String label = (dto.getVariantLabel() != null && !dto.getVariantLabel().isBlank())
                    ? dto.getVariantLabel().trim().toUpperCase()
                    : String.valueOf((char) ('A' + i));

            String templateIdOrName = dto.getTemplateId();
            if (templateIdOrName == null || templateIdOrName.isBlank()) {
                throw new IllegalArgumentException("Variant " + label + " is missing templateId");
            }

            WhatsAppTemplate template = resolveTemplate(templateIdOrName, actor, tenantId);
            if (template == null) {
                throw new IllegalArgumentException("Template '" + templateIdOrName + "' not found for variant " + label);
            }

            WhatsAppTemplateSnapshot snapshot = createSnapshotForTemplate(template);

            WhatsAppCampaignVariant variant = WhatsAppCampaignVariant.builder()
                    .tenant(campaign.getTenant())
                    .campaign(campaign)
                    .variantLabel(label)
                    .templateName(template.getName())
                    .templateSnapshot(snapshot)
                    .variableMappingJson(dto.getVariableMappingJson())
                    .samplePercentage(dto.getSamplePercentage() != null ? dto.getSamplePercentage() : 50)
                    .sampleCount(0)
                    .sentCount(0)
                    .deliveredCount(0)
                    .readCount(0)
                    .clickCount(0)
                    .isWinner(false)
                    .createdAt(LocalDateTime.now())
                    .build();

            savedVariants.add(variantRepository.save(variant));
        }

        log.info("🧪 [AbTesting] Created {} variants for campaignId={}", savedVariants.size(), campaign.getId());
        return savedVariants;
    }

    /**
     * Partitions the audience into test group vs holdout group, and evenly distributes
     * the test group across the configured variants with personalization re-rendering.
     */
    @Transactional
    public void partitionAudience(WhatsAppCampaign campaign, List<WhatsAppCampaignRecipient> validRecipients) {
        if (!Boolean.TRUE.equals(campaign.getIsAbTest()) || validRecipients == null || validRecipients.isEmpty()) {
            return;
        }

        List<WhatsAppCampaignVariant> variants = variantRepository.findByCampaignOrderByVariantLabelAsc(campaign);
        if (variants == null || variants.size() < 2) {
            log.warn("⚠️ [AbTesting] Campaign {} has isAbTest=true but found {} variants. Skipping partitioning.",
                    campaign.getId(), variants != null ? variants.size() : 0);
            return;
        }

        int totalRecipients = validRecipients.size();
        int testPercentage = (campaign.getAbTestPercentage() != null && campaign.getAbTestPercentage() > 0)
                ? Math.min(100, campaign.getAbTestPercentage())
                : 20;

        int testSize = (int) Math.round((totalRecipients * testPercentage) / 100.0);
        // Ensure test size covers at least 1 recipient per variant if total permits
        testSize = Math.max(variants.size(), Math.min(totalRecipients, testSize));

        // Cryptographically secure shuffle to eliminate systematic audience bias
        Collections.shuffle(validRecipients, SECURE_RANDOM);

        List<WhatsAppCampaignRecipient> testGroup = validRecipients.subList(0, testSize);
        List<WhatsAppCampaignRecipient> holdoutGroup = validRecipients.subList(testSize, totalRecipients);

        // 1. Process holdout recipients: mark isHoldout = true, pending winner declaration
        for (WhatsAppCampaignRecipient holdout : holdoutGroup) {
            holdout.setIsHoldout(true);
            holdout.setVariant(null);
            holdout.setStatus(WhatsAppCampaignRecipient.RecipientStatus.PENDING);
        }

        // Reset variant sample counts
        for (WhatsAppCampaignVariant v : variants) {
            v.setSampleCount(0);
        }

        // 2. Process test group recipients: distribute across variants
        for (int i = 0; i < testGroup.size(); i++) {
            WhatsAppCampaignRecipient recipient = testGroup.get(i);
            WhatsAppCampaignVariant assignedVariant = variants.get(i % variants.size());

            recipient.setIsHoldout(false);
            recipient.setVariant(assignedVariant);
            recipient.setStatus(WhatsAppCampaignRecipient.RecipientStatus.PENDING);

            // Re-render parameters according to variant's template and mapping
            Lead lead = null;
            if (recipient.getContact() != null && recipient.getContact().getId() != null) {
                lead = leadRepository.findTopByContactOrderByCreatedAtDesc(recipient.getContact()).orElse(null);
            }

            List<String> renderedParams = personalizationEngine.renderTemplateParameters(
                    assignedVariant.getVariableMappingJson(),
                    recipient.getContact(),
                    lead,
                    campaign.getOwner()
            );

            try {
                recipient.setResolvedVariablesJson(objectMapper.writeValueAsString(renderedParams));
            } catch (Exception e) {
                log.warn("[AbTesting] Failed to serialize variables for phone={}", recipient.getPhoneNumber());
            }

            assignedVariant.setSampleCount(assignedVariant.getSampleCount() + 1);
        }

        variantRepository.saveAll(variants);
        recipientRepository.saveAll(validRecipients);

        log.info("📊 [AbTesting] Partitioned audience for campaignId={}: Total={}, TestSample={}, Holdout={}, Variants={}",
                campaign.getId(), totalRecipients, testSize, holdoutGroup.size(), variants.size());
    }

    /**
     * Schedules the automatic winner evaluation Quartz job upon campaign execution.
     */
    public void scheduleEvaluationJob(WhatsAppCampaign campaign, LocalDateTime startTime) {
        if (!Boolean.TRUE.equals(campaign.getIsAbTest())) {
            return;
        }

        int evalHours = (campaign.getAbEvaluationHours() != null && campaign.getAbEvaluationHours() > 0)
                ? campaign.getAbEvaluationHours()
                : 4;

        LocalDateTime evalEndsAt = startTime.plusHours(evalHours);
        campaign.setAbEvaluationEndsAt(evalEndsAt);
        campaign.setAbStatus("TESTING");
        campaignRepository.save(campaign);

        String tenantTz = (campaign.getTenant() != null && campaign.getTenant().getTimezone() != null)
                ? campaign.getTenant().getTimezone()
                : "Asia/Kolkata";
        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(tenantTz);
        } catch (Exception e) {
            zoneId = ZoneId.of("Asia/Kolkata");
        }

        Date evalDate = Date.from(evalEndsAt.atZone(zoneId).toInstant());

        distributedSchedulerService.scheduleOneTimeJob(
                "ab-winner-" + campaign.getId(),
                WhatsAppAbWinnerJob.class,
                evalDate,
                Map.of("campaignId", campaign.getId().toString())
        );

        log.info("⏰ [AbTesting] Scheduled auto-winner evaluation for campaignId={} at {}", campaign.getId(), evalEndsAt);
    }

    /**
     * Evaluates all variants according to the configured metric (CTR or Read Rate),
     * promotes the winning variant, and immediately dispatches the holdout audience.
     */
    @Transactional
    public WhatsAppCampaignVariant evaluateAndPromoteWinner(UUID campaignId, UUID manualChosenVariantId, User actor) {
        WhatsAppCampaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new IllegalArgumentException("Campaign not found with ID: " + campaignId));

        if (!Boolean.TRUE.equals(campaign.getIsAbTest())) {
            throw new IllegalStateException("Campaign is not configured as an A/B test");
        }

        if ("WINNER_PROMOTED".equalsIgnoreCase(campaign.getAbStatus()) && campaign.getWinningVariantId() != null) {
            log.info("ℹ️ [AbTesting] Winner already promoted for campaignId={}", campaignId);
            return variantRepository.findById(campaign.getWinningVariantId()).orElse(null);
        }

        List<WhatsAppCampaignVariant> variants = variantRepository.findByCampaignOrderByVariantLabelAsc(campaign);
        if (variants.isEmpty()) {
            throw new IllegalStateException("No variants found for campaign: " + campaignId);
        }

        WhatsAppCampaignVariant winningVariant;

        if (manualChosenVariantId != null) {
            winningVariant = variants.stream()
                    .filter(v -> v.getId().equals(manualChosenVariantId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Specified variant ID not found for campaign"));
            log.info("🎯 [AbTesting] Manual winner promotion selected Variant {} for campaignId={}",
                    winningVariant.getVariantLabel(), campaignId);
        } else {
            // Auto evaluation by metric
            Comparator<WhatsAppCampaignVariant> comparator;
            String metric = campaign.getAbWinnerMetric() != null ? campaign.getAbWinnerMetric().toUpperCase() : "CLICK_RATE";

            if ("READ_RATE".equals(metric)) {
                comparator = Comparator.comparingDouble(WhatsAppCampaignVariant::getReadRate)
                        .thenComparingDouble(WhatsAppCampaignVariant::getCtr)
                        .thenComparing(v -> "A".equalsIgnoreCase(v.getVariantLabel()) ? 1 : 0);
            } else {
                // Default to CLICK_RATE
                comparator = Comparator.comparingDouble(WhatsAppCampaignVariant::getCtr)
                        .thenComparingDouble(WhatsAppCampaignVariant::getReadRate)
                        .thenComparing(v -> "A".equalsIgnoreCase(v.getVariantLabel()) ? 1 : 0);
            }

            winningVariant = variants.stream()
                    .max(comparator)
                    .orElse(variants.get(0));

            log.info("🏆 [AbTesting] Auto-evaluated winner for campaignId={} using metric {}: Variant {} (CTR={:.2f}%, ReadRate={:.2f}%)",
                    campaignId, metric, winningVariant.getVariantLabel(), winningVariant.getCtr(), winningVariant.getReadRate());
        }

        // 1. Mark winner in variants table
        for (WhatsAppCampaignVariant v : variants) {
            v.setIsWinner(v.getId().equals(winningVariant.getId()));
        }
        variantRepository.saveAll(variants);

        // 2. Update campaign state
        campaign.setWinningVariantId(winningVariant.getId());
        campaign.setAbStatus("WINNER_PROMOTED");
        campaignRepository.save(campaign);

        // 3. Dispatch holdout recipients with winning variant template
        List<WhatsAppCampaignRecipient> holdouts = recipientRepository.findByCampaignAndIsHoldout(campaign, true);
        if (!holdouts.isEmpty()) {
            for (WhatsAppCampaignRecipient holdout : holdouts) {
                holdout.setIsHoldout(false);
                holdout.setVariant(winningVariant);

                Lead lead = null;
                if (holdout.getContact() != null && holdout.getContact().getId() != null) {
                    lead = leadRepository.findTopByContactOrderByCreatedAtDesc(holdout.getContact()).orElse(null);
                }

                List<String> renderedParams = personalizationEngine.renderTemplateParameters(
                        winningVariant.getVariableMappingJson(),
                        holdout.getContact(),
                        lead,
                        campaign.getOwner()
                );

                try {
                    holdout.setResolvedVariablesJson(objectMapper.writeValueAsString(renderedParams));
                } catch (Exception e) {
                    log.warn("[AbTesting] Failed to serialize winning variables for phone={}", holdout.getPhoneNumber());
                }
            }

            recipientRepository.saveAll(holdouts);
            queueProducer.queueHoldoutRecipients(campaign, holdouts);
            log.info("🚀 [AbTesting] Dispatched {} holdout recipients with winning Variant {} for campaignId={}",
                    holdouts.size(), winningVariant.getVariantLabel(), campaignId);
        }

        auditService.logAction(campaign, actor, WhatsAppCampaignAuditLog.Action.RESUMED,
                "{\"winnerVariant\": \"" + winningVariant.getVariantLabel() + "\", \"holdoutDispatched\": " + holdouts.size() + "}");

        return winningVariant;
    }

    /**
     * Computes live comparative telemetry for all variants of an A/B campaign.
     */
    @Transactional(readOnly = true)
    public CampaignAbMetricsDto getAbMetrics(UUID campaignId, User actor) {
        WhatsAppCampaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new IllegalArgumentException("Campaign not found with ID: " + campaignId));

        List<WhatsAppCampaignVariant> variants = variantRepository.findByCampaignOrderByVariantLabelAsc(campaign);

        // Determine current leader
        Comparator<WhatsAppCampaignVariant> comparator;
        String metric = campaign.getAbWinnerMetric() != null ? campaign.getAbWinnerMetric().toUpperCase() : "CLICK_RATE";
        if ("READ_RATE".equals(metric)) {
            comparator = Comparator.comparingDouble(WhatsAppCampaignVariant::getReadRate)
                    .thenComparingDouble(WhatsAppCampaignVariant::getCtr)
                    .thenComparing(v -> "A".equalsIgnoreCase(v.getVariantLabel()) ? 1 : 0);
        } else {
            comparator = Comparator.comparingDouble(WhatsAppCampaignVariant::getCtr)
                    .thenComparingDouble(WhatsAppCampaignVariant::getReadRate)
                    .thenComparing(v -> "A".equalsIgnoreCase(v.getVariantLabel()) ? 1 : 0);
        }

        WhatsAppCampaignVariant currentLeader = variants.stream().max(comparator).orElse(null);

        List<CampaignAbMetricsDto.VariantMetricsDto> variantMetrics = new ArrayList<>();
        int testSampleCount = 0;

        for (WhatsAppCampaignVariant v : variants) {
            testSampleCount += v.getSampleCount() != null ? v.getSampleCount() : 0;
            boolean isWinner = Boolean.TRUE.equals(v.getIsWinner()) || (campaign.getWinningVariantId() != null && campaign.getWinningVariantId().equals(v.getId()));
            boolean isLeader = currentLeader != null && currentLeader.getId().equals(v.getId());

            variantMetrics.add(CampaignAbMetricsDto.VariantMetricsDto.builder()
                    .id(v.getId())
                    .variantLabel(v.getVariantLabel())
                    .templateName(v.getTemplateName())
                    .sampleCount(v.getSampleCount() != null ? v.getSampleCount() : 0)
                    .sentCount(v.getSentCount() != null ? v.getSentCount() : 0)
                    .deliveredCount(v.getDeliveredCount() != null ? v.getDeliveredCount() : 0)
                    .readCount(v.getReadCount() != null ? v.getReadCount() : 0)
                    .clickCount(v.getClickCount() != null ? v.getClickCount() : 0)
                    .readRate(Math.round(v.getReadRate() * 100.0) / 100.0)
                    .ctr(Math.round(v.getCtr() * 100.0) / 100.0)
                    .isWinner(isWinner)
                    .isLeader(isLeader)
                    .build());
        }

        long holdoutCount = recipientRepository.countByCampaignAndIsHoldout(campaign, true);
        long totalAudience = recipientRepository.countByCampaignId(campaign.getId());

        Long remainingMinutes = null;
        if (campaign.getAbEvaluationEndsAt() != null) {
            LocalDateTime now = LocalDateTime.now();
            if (campaign.getAbEvaluationEndsAt().isAfter(now)) {
                remainingMinutes = Duration.between(now, campaign.getAbEvaluationEndsAt()).toMinutes();
            } else {
                remainingMinutes = 0L;
            }
        }

        String winningLabel = null;
        if (campaign.getWinningVariantId() != null) {
            for (WhatsAppCampaignVariant v : variants) {
                if (v.getId().equals(campaign.getWinningVariantId())) {
                    winningLabel = v.getVariantLabel();
                    break;
                }
            }
        }

        return CampaignAbMetricsDto.builder()
                .campaignId(campaign.getId())
                .campaignName(campaign.getName())
                .abStatus(campaign.getAbStatus())
                .isAbTest(Boolean.TRUE.equals(campaign.getIsAbTest()))
                .winnerMetric(metric)
                .evaluationEndsAt(campaign.getAbEvaluationEndsAt())
                .remainingMinutes(remainingMinutes)
                .winningVariantLabel(winningLabel)
                .totalAudience((int) totalAudience)
                .testSampleCount(testSampleCount)
                .holdoutCount((int) holdoutCount)
                .variants(variantMetrics)
                .build();
    }

    private WhatsAppTemplate resolveTemplate(String templateIdOrName, User owner, UUID tenantId) {
        if (templateIdOrName.trim().length() == 36 && templateIdOrName.contains("-")) {
            try {
                UUID uuid = UUID.fromString(templateIdOrName.trim());
                Optional<WhatsAppTemplate> opt = templateRepository.findById(uuid);
                if (opt.isPresent()) return opt.get();
            } catch (Exception ignored) {}
        }

        String cleanName = templateIdOrName.trim();
        if (tenantId != null) {
            Optional<WhatsAppTemplate> opt = templateRepository.findByNameAndTenantId(cleanName, tenantId);
            if (opt.isPresent()) return opt.get();
        }
        if (owner != null) {
            Optional<WhatsAppTemplate> opt = templateRepository.findByNameAndOwner(cleanName, owner);
            if (opt.isPresent()) return opt.get();
        }
        return templateRepository.findFirstByName(cleanName).orElse(null);
    }

    private WhatsAppTemplateSnapshot createSnapshotForTemplate(WhatsAppTemplate template) {
        Optional<WhatsAppTemplateSnapshot> latest = templateSnapshotRepository
                .findFirstByOriginalTemplateIdOrderByVersionDesc(template.getId());

        int nextVersion = latest.map(s -> s.getVersion() + 1).orElse(1);

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
}
