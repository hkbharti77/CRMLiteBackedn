package com.chatcrmlite.backend.services.email;

import com.chatcrmlite.backend.dto.EmailAbMetricsDto;
import com.chatcrmlite.backend.dto.EmailVariantDto;
import com.chatcrmlite.backend.dto.EmailVariantInput;
import com.chatcrmlite.backend.models.CustomEmail;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.email.EmailCampaignRecipient;
import com.chatcrmlite.backend.models.email.EmailCampaignVariant;
import com.chatcrmlite.backend.repositories.CustomEmailRepository;
import com.chatcrmlite.backend.repositories.email.EmailCampaignRecipientRepository;
import com.chatcrmlite.backend.repositories.email.EmailCampaignVariantRepository;
import com.chatcrmlite.backend.services.CustomEmailService;
import com.chatcrmlite.backend.services.DistributedSchedulerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

@Slf4j
@Service
public class EmailAbTestingService {

    private final CustomEmailRepository campaignRepository;
    private final EmailCampaignVariantRepository variantRepository;
    private final EmailCampaignRecipientRepository recipientRepository;
    private final DistributedSchedulerService distributedSchedulerService;
    private final CustomEmailService customEmailService;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public EmailAbTestingService(CustomEmailRepository campaignRepository,
                                 EmailCampaignVariantRepository variantRepository,
                                 EmailCampaignRecipientRepository recipientRepository,
                                 DistributedSchedulerService distributedSchedulerService,
                                 @Lazy CustomEmailService customEmailService) {
        this.campaignRepository = campaignRepository;
        this.variantRepository = variantRepository;
        this.recipientRepository = recipientRepository;
        this.distributedSchedulerService = distributedSchedulerService;
        this.customEmailService = customEmailService;
    }

    /**
     * Resolves and persists multi-variant snapshot entities for an email A/B campaign.
     */
    @Transactional
    public List<EmailCampaignVariant> createVariantsForCampaign(CustomEmail campaign, List<EmailVariantInput> variantInputs, User actor) {
        if (variantInputs == null || variantInputs.size() < 2) {
            throw new IllegalArgumentException("Email A/B testing requires at least 2 variants (e.g. Variant A and Variant B)");
        }

        List<EmailCampaignVariant> savedVariants = new ArrayList<>();

        for (int i = 0; i < variantInputs.size(); i++) {
            EmailVariantInput input = variantInputs.get(i);
            String code = (input.getVariantCode() != null && !input.getVariantCode().isBlank())
                    ? input.getVariantCode().trim().toUpperCase()
                    : String.valueOf((char) ('A' + i));

            String name = (input.getName() != null && !input.getName().isBlank())
                    ? input.getName().trim()
                    : "Variant " + code;

            String subject = (input.getSubject() != null && !input.getSubject().isBlank())
                    ? input.getSubject().trim()
                    : campaign.getSubject();

            String body = (input.getBody() != null && !input.getBody().isBlank())
                    ? input.getBody()
                    : campaign.getBody();

            String builderJson = (input.getBuilderJson() != null && !input.getBuilderJson().isBlank())
                    ? input.getBuilderJson()
                    : campaign.getBuilderJson();

            EmailCampaignVariant variant = EmailCampaignVariant.builder()
                    .campaign(campaign)
                    .variantCode(code)
                    .name(name)
                    .subject(subject)
                    .body(body)
                    .builderJson(builderJson)
                    .sampleCount(0)
                    .sentCount(0)
                    .deliveredCount(0)
                    .openedCount(0)
                    .clickedCount(0)
                    .isWinner(false)
                    .createdAt(LocalDateTime.now())
                    .build();

            if (campaign.getTenant() != null) {
                variant.setTenant(campaign.getTenant());
            }

            savedVariants.add(variantRepository.save(variant));
        }

        log.info("🧪 [EmailAbTesting] Created {} variants for campaignId={}", savedVariants.size(), campaign.getId());
        return savedVariants;
    }

    /**
     * Cryptographically partitions audience into test sample vs holdout pool.
     */
    @Transactional
    public void partitionAudience(CustomEmail campaign, List<EmailCampaignRecipient> validRecipients) {
        if (!Boolean.TRUE.equals(campaign.getIsAbTest()) || validRecipients == null || validRecipients.isEmpty()) {
            return;
        }

        List<EmailCampaignVariant> variants = variantRepository.findByCampaignIdOrderByVariantCodeAsc(campaign.getId());
        if (variants == null || variants.size() < 2) {
            log.warn("⚠️ [EmailAbTesting] Campaign {} has isAbTest=true but found {} variants. Skipping partitioning.",
                    campaign.getId(), variants != null ? variants.size() : 0);
            return;
        }

        int totalRecipients = validRecipients.size();
        int testPercentage = campaign.getAbTestPercentage() > 0
                ? Math.min(100, campaign.getAbTestPercentage())
                : 20;

        int testSize = (int) Math.round((totalRecipients * testPercentage) / 100.0);
        testSize = Math.max(variants.size(), Math.min(totalRecipients, testSize));

        // Cryptographically secure shuffle to remove bias
        Collections.shuffle(validRecipients, SECURE_RANDOM);

        List<EmailCampaignRecipient> testGroup = validRecipients.subList(0, testSize);
        List<EmailCampaignRecipient> holdoutGroup = validRecipients.subList(testSize, totalRecipients);

        // Holdout group: marked as holdout, pending winner declaration
        for (EmailCampaignRecipient holdout : holdoutGroup) {
            holdout.setIsHoldout(true);
            holdout.setVariantId(null);
            holdout.setDeliveryStatus(EmailCampaignRecipient.DeliveryStatus.PENDING);
        }

        // Reset variant sample counts
        for (EmailCampaignVariant v : variants) {
            v.setSampleCount(0);
        }

        // Test group: round-robin across variants
        for (int i = 0; i < testGroup.size(); i++) {
            EmailCampaignRecipient recipient = testGroup.get(i);
            EmailCampaignVariant assignedVariant = variants.get(i % variants.size());

            recipient.setIsHoldout(false);
            recipient.setVariantId(assignedVariant.getId());
            recipient.setDeliveryStatus(EmailCampaignRecipient.DeliveryStatus.PENDING);

            assignedVariant.setSampleCount(assignedVariant.getSampleCount() + 1);
        }

        variantRepository.saveAll(variants);
        recipientRepository.saveAll(validRecipients);

        log.info("📊 [EmailAbTesting] Partitioned audience for campaignId={}: Total={}, TestSample={}, Holdout={}, Variants={}",
                campaign.getId(), totalRecipients, testSize, holdoutGroup.size(), variants.size());
    }

    /**
     * Schedules Quartz auto-winner evaluation job.
     */
    public void scheduleEvaluationJob(CustomEmail campaign, LocalDateTime startTime) {
        if (!Boolean.TRUE.equals(campaign.getIsAbTest())) {
            return;
        }

        int evalHours = campaign.getAbEvaluationHours() > 0
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
                "email-ab-winner-" + campaign.getId(),
                EmailAbWinnerJob.class,
                evalDate,
                Map.of("campaignId", campaign.getId().toString())
        );

        log.info("⏰ [EmailAbTesting] Scheduled auto-winner evaluation for email campaignId={} at {}", campaign.getId(), evalEndsAt);
    }

    /**
     * Evaluates all variants according to configured metric (OPEN_RATE or CLICK_RATE),
     * promotes the winning variant, and immediately dispatches the holdout audience.
     */
    @Transactional
    public EmailCampaignVariant evaluateAndPromoteWinner(UUID campaignId, UUID manualChosenVariantId, User actor) {
        CustomEmail campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new IllegalArgumentException("Email campaign not found with ID: " + campaignId));

        if (!Boolean.TRUE.equals(campaign.getIsAbTest())) {
            throw new IllegalStateException("Campaign is not configured as an A/B test");
        }

        if ("WINNER_PROMOTED".equalsIgnoreCase(campaign.getAbStatus()) && campaign.getWinningVariantId() != null) {
            log.info("ℹ️ [EmailAbTesting] Winner already promoted for campaignId={}", campaignId);
            return variantRepository.findById(campaign.getWinningVariantId()).orElse(null);
        }

        List<EmailCampaignVariant> variants = variantRepository.findByCampaignIdOrderByVariantCodeAsc(campaign.getId());
        if (variants.isEmpty()) {
            throw new IllegalStateException("No variants found for campaign: " + campaignId);
        }

        EmailCampaignVariant winningVariant;

        if (manualChosenVariantId != null) {
            winningVariant = variants.stream()
                    .filter(v -> v.getId().equals(manualChosenVariantId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Specified variant ID not found for campaign"));
            log.info("🎯 [EmailAbTesting] Manual winner promotion selected Variant {} for campaignId={}",
                    winningVariant.getVariantCode(), campaignId);
        } else {
            // Auto evaluation by metric
            Comparator<EmailCampaignVariant> comparator;
            String metric = campaign.getAbWinnerMetric() != null ? campaign.getAbWinnerMetric().toUpperCase() : "OPEN_RATE";

            if ("CLICK_RATE".equals(metric)) {
                comparator = Comparator.comparingDouble(EmailCampaignVariant::getCtr)
                        .thenComparingDouble(EmailCampaignVariant::getOpenRate)
                        .thenComparing(v -> "A".equalsIgnoreCase(v.getVariantCode()) ? 1 : 0);
            } else {
                // Default to OPEN_RATE
                comparator = Comparator.comparingDouble(EmailCampaignVariant::getOpenRate)
                        .thenComparingDouble(EmailCampaignVariant::getCtr)
                        .thenComparing(v -> "A".equalsIgnoreCase(v.getVariantCode()) ? 1 : 0);
            }

            winningVariant = variants.stream()
                    .max(comparator)
                    .orElse(variants.get(0));

            log.info("🏆 [EmailAbTesting] Auto-evaluated winner for campaignId={} using metric {}: Variant {} (OpenRate={:.2f}%, CTR={:.2f}%)",
                    campaignId, metric, winningVariant.getVariantCode(), winningVariant.getOpenRate(), winningVariant.getCtr());
        }

        // 1. Mark winner in variants table atomically
        for (EmailCampaignVariant v : variants) {
            v.setWinner(v.getId().equals(winningVariant.getId()));
        }
        variantRepository.saveAll(variants);

        // 2. Update campaign state
        campaign.setWinningVariantId(winningVariant.getId());
        campaign.setAbStatus("WINNER_PROMOTED");
        campaignRepository.save(campaign);

        // 3. Atomically release holdout recipients to the winning variant
        int releasedCount = recipientRepository.releaseHoldoutToWinner(
                campaign.getId(),
                winningVariant.getId(),
                EmailCampaignRecipient.DeliveryStatus.PENDING
        );
        log.info("🚀 [EmailAbTesting] Released {} holdout recipients to winning Variant {} for campaignId={}",
                releasedCount, winningVariant.getVariantCode(), campaignId);

        // 4. Trigger dispatch of holdout recipients
        if (releasedCount > 0) {
            customEmailService.startCampaignExecution(campaign.getId());
        }

        return winningVariant;
    }

    /**
     * Computes live comparative telemetry for all variants of an email A/B campaign.
     */
    @Transactional(readOnly = true)
    public EmailAbMetricsDto getAbMetrics(UUID campaignId, User actor) {
        CustomEmail campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new IllegalArgumentException("Email campaign not found with ID: " + campaignId));

        List<EmailCampaignVariant> variants = variantRepository.findByCampaignIdOrderByVariantCodeAsc(campaign.getId());

        // Determine current leader
        Comparator<EmailCampaignVariant> comparator;
        String metric = campaign.getAbWinnerMetric() != null ? campaign.getAbWinnerMetric().toUpperCase() : "OPEN_RATE";
        if ("CLICK_RATE".equals(metric)) {
            comparator = Comparator.comparingDouble(EmailCampaignVariant::getCtr)
                    .thenComparingDouble(EmailCampaignVariant::getOpenRate)
                    .thenComparing(v -> "A".equalsIgnoreCase(v.getVariantCode()) ? 1 : 0);
        } else {
            comparator = Comparator.comparingDouble(EmailCampaignVariant::getOpenRate)
                    .thenComparingDouble(EmailCampaignVariant::getCtr)
                    .thenComparing(v -> "A".equalsIgnoreCase(v.getVariantCode()) ? 1 : 0);
        }

        EmailCampaignVariant currentLeader = variants.stream().max(comparator).orElse(null);

        List<EmailVariantDto> variantDtos = new ArrayList<>();
        int testSampleCount = 0;

        for (EmailCampaignVariant v : variants) {
            testSampleCount += v.getSampleCount();
            boolean isWinner = v.isWinner() || (campaign.getWinningVariantId() != null && campaign.getWinningVariantId().equals(v.getId()));
            boolean isLeader = currentLeader != null && currentLeader.getId().equals(v.getId());

            variantDtos.add(EmailVariantDto.builder()
                    .id(v.getId())
                    .variantCode(v.getVariantCode())
                    .name(v.getName())
                    .subject(v.getSubject())
                    .body(v.getBody())
                    .builderJson(v.getBuilderJson())
                    .sampleCount(v.getSampleCount())
                    .sentCount(v.getSentCount())
                    .deliveredCount(v.getDeliveredCount())
                    .openedCount(v.getOpenedCount())
                    .clickedCount(v.getClickedCount())
                    .openRate(Math.round(v.getOpenRate() * 100.0) / 100.0)
                    .ctr(Math.round(v.getCtr() * 100.0) / 100.0)
                    .isWinner(isWinner)
                    .isLeader(isLeader)
                    .build());
        }

        long holdoutCount = recipientRepository.countByCampaignIdAndIsHoldout(campaign.getId(), true);
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

        String winningCode = null;
        String winningName = null;
        if (campaign.getWinningVariantId() != null) {
            for (EmailCampaignVariant v : variants) {
                if (v.getId().equals(campaign.getWinningVariantId())) {
                    winningCode = v.getVariantCode();
                    winningName = v.getName();
                    break;
                }
            }
        }

        return EmailAbMetricsDto.builder()
                .campaignId(campaign.getId())
                .campaignName(campaign.getName())
                .abStatus(campaign.getAbStatus())
                .isAbTest(Boolean.TRUE.equals(campaign.getIsAbTest()))
                .winnerMetric(metric)
                .evaluationEndsAt(campaign.getAbEvaluationEndsAt())
                .remainingMinutes(remainingMinutes)
                .winningVariantId(campaign.getWinningVariantId())
                .winningVariantCode(winningCode)
                .winningVariantName(winningName)
                .totalAudience((int) totalAudience)
                .testSampleCount(testSampleCount)
                .holdoutCount((int) holdoutCount)
                .variants(variantDtos)
                .build();
    }

    public EmailVariantDto toVariantDto(EmailCampaignVariant v) {
        if (v == null) return null;
        return EmailVariantDto.builder()
                .id(v.getId())
                .variantCode(v.getVariantCode())
                .name(v.getName())
                .subject(v.getSubject())
                .body(v.getBody())
                .builderJson(v.getBuilderJson())
                .sampleCount(v.getSampleCount())
                .sentCount(v.getSentCount())
                .deliveredCount(v.getDeliveredCount())
                .openedCount(v.getOpenedCount())
                .clickedCount(v.getClickedCount())
                .openRate(Math.round(v.getOpenRate() * 100.0) / 100.0)
                .ctr(Math.round(v.getCtr() * 100.0) / 100.0)
                .isWinner(v.isWinner())
                .build();
    }
}
