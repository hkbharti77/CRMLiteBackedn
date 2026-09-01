package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.dto.CustomEmailDTO;
import com.chatcrmlite.backend.dto.CustomEmailRequest;
import com.chatcrmlite.backend.dto.AiContentResponse;
import com.chatcrmlite.backend.dto.AiTemplateResponse;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.CustomEmail;
import com.chatcrmlite.backend.models.CustomEmail.EmailStatus;
import com.chatcrmlite.backend.models.CustomEmail.RecipientMode;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.email.EmailCampaignSnapshot;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.CustomEmailRepository;
import com.chatcrmlite.backend.repositories.TenantRepository;
import com.chatcrmlite.backend.repositories.email.EmailCampaignSnapshotRepository;
import com.chatcrmlite.backend.services.tenant.QuotaEnforcerService;
import com.chatcrmlite.backend.services.email.EmailSuppressionService;
import com.chatcrmlite.backend.services.email.EmailTrackingService;
import com.chatcrmlite.backend.services.email.EmailAudienceResolver;
import com.chatcrmlite.backend.services.email.EmailCampaignStateService;
import com.chatcrmlite.backend.services.email.EmailCampaignAuditService;
import com.chatcrmlite.backend.repositories.email.EmailCampaignRecipientRepository;
import com.chatcrmlite.backend.models.email.EmailCampaignRecipient;
import com.chatcrmlite.backend.models.email.EmailCampaignRecipient.DeliveryStatus;
import com.chatcrmlite.backend.models.email.EmailCampaignAuditLog;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.output.TokenUsage;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class CustomEmailService {
    private static final Logger log = LoggerFactory.getLogger(CustomEmailService.class);

    private final CustomEmailRepository customEmailRepository;
    private final ContactRepository     contactRepository;
    private final TenantRepository      tenantRepository;
    private final JavaMailSender        mailSender;
    private final TemplateEngine        templateEngine;
    private final QuotaEnforcerService  quotaEnforcerService;
    private final EmailSuppressionService suppressionService;
    private final EmailTrackingService    trackingService;
    private final EmailCampaignRecipientRepository recipientRepository;
    private final EmailAudienceResolver audienceResolver;
    private final EmailCampaignSnapshotRepository snapshotRepository;
    private final EmailCampaignStateService stateService;
    private final EmailCampaignAuditService auditService;
    private final DistributedSchedulerService distributedSchedulerService;
    private final com.chatcrmlite.backend.services.email.EmailProviderService emailProviderService;
    private final com.chatcrmlite.backend.services.email.ProviderFactory providerFactory;

    @Value("${SENDER_EMAIL:${spring.mail.username:no-reply@gyanvaniai.online}}")
    private String fromAddress;

    @Value("${platform.brand.url:https://gyanvaniai.online}")
    private String platformBrandUrl;

    @Autowired
    public CustomEmailService(CustomEmailRepository customEmailRepository,
                             ContactRepository contactRepository,
                             TenantRepository tenantRepository,
                             JavaMailSender mailSender,
                             TemplateEngine templateEngine,
                             QuotaEnforcerService quotaEnforcerService,
                             EmailSuppressionService suppressionService,
                             EmailTrackingService trackingService,
                             EmailCampaignRecipientRepository recipientRepository,
                             EmailAudienceResolver audienceResolver,
                             EmailCampaignSnapshotRepository snapshotRepository,
                             EmailCampaignStateService stateService,
                             EmailCampaignAuditService auditService,
                             DistributedSchedulerService distributedSchedulerService,
                             com.chatcrmlite.backend.services.email.EmailProviderService emailProviderService,
                             com.chatcrmlite.backend.services.email.ProviderFactory providerFactory) {
        this.customEmailRepository = customEmailRepository;
        this.contactRepository = contactRepository;
        this.tenantRepository = tenantRepository;
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
        this.quotaEnforcerService = quotaEnforcerService;
        this.suppressionService = suppressionService;
        this.trackingService = trackingService;
        this.recipientRepository = recipientRepository;
        this.audienceResolver = audienceResolver;
        this.snapshotRepository = snapshotRepository;
        this.stateService = stateService;
        this.auditService = auditService;
        this.distributedSchedulerService = distributedSchedulerService;
        this.emailProviderService = emailProviderService;
        this.providerFactory = providerFactory;
    }

    @Autowired(required = false)
    private ChatLanguageModel chatLanguageModel;

    @Autowired
    private AIQuotaService aiQuotaService;

    @Autowired
    private TokenBudgetService tokenBudgetService;

    @Autowired
    private CostTracker costTracker;

    @Autowired
    private com.chatcrmlite.backend.services.tenant.TenantTierService tenantTierService;

    @Autowired
    private ObjectMapper objectMapper;

    private User.PlanType resolvePlan(User user) {
        if (user.getRole() == User.Role.SUPER_ADMIN) {
            return User.PlanType.ENTERPRISE;
        }
        if (user.getTenant() != null && user.getTenant().getId() != null) {
            return tenantTierService.getTier(user.getTenant().getId());
        }
        return user.getPlanType() != null ? user.getPlanType() : User.PlanType.FREE;
    }

    public AiContentResponse generateAiContent(User user, String prompt) {
        aiQuotaService.checkAndEnforceQuota(user.getTenant() != null ? user.getTenant().getId() : user.getId(), resolvePlan(user));

        String systemInstruction = 
            "You are a professional email marketing assistant. " +
            "Generate email subject, email body, a call-to-action button label, and a call-to-action redirect URL based on the user's instructions. " +
            "If the user specifies a link in their instruction, extract and return it as 'ctaUrl'. Otherwise, return an empty string. " +
            "CRITICAL: The 'body' MUST be generated as HTML fragments only (e.g., <p>, <strong>, <br>, <a>, <ul>, <li>). " +
            "EXPLICITLY PROHIBITED: Do not include <html>, <head>, <body>, <style>, <script>, or <!DOCTYPE>. " +
            "You must return the result as a valid JSON object with exactly four keys: 'subject', 'body', 'ctaLabel', and 'ctaUrl'.";
        
        String userMsg = String.format(
            "Write email content based on the following instruction: \n\"%s\"\n\nFormat: {\"subject\": \"...\", \"body\": \"...\", \"ctaLabel\": \"...\", \"ctaUrl\": \"...\"}", 
            prompt
        );

        String fallbackUrl = (platformBrandUrl != null && !platformBrandUrl.isBlank()) ? platformBrandUrl : "https://gyanvaniai.online";
        Map<String, String> fallback = Map.of(
            "subject", "Exclusive Offer for {{lead.name}}",
            "body", "Hi {{lead.name}},<br><br>Thank you for reaching out. In response to: <em>\"" + prompt.replace("<", "&lt;").replace(">", "&gt;") + "\"</em>, we have created this custom campaign for you.<br><br>Contact us today for more details!",
            "ctaLabel", "Claim Offer →",
            "ctaUrl", fallbackUrl
        );

        Map<String, String> result = callAiHelper(user, systemInstruction, userMsg, fallback, prompt);
        
        // Sanitize dangerous HTML tags (basic backend sanitization)
        String sanitizedBody = sanitizeHtml(result.getOrDefault("body", fallback.get("body")));

        return AiContentResponse.builder()
                .subject(result.getOrDefault("subject", fallback.get("subject")))
                .htmlContent(sanitizedBody)
                .ctaLabel(result.getOrDefault("ctaLabel", fallback.get("ctaLabel")))
                .ctaUrl(result.getOrDefault("ctaUrl", fallback.get("ctaUrl")))
                .build();
    }

    public AiTemplateResponse generateAiTemplate(User user, String prompt) {
        aiQuotaService.checkAndEnforceQuota(user.getTenant() != null ? user.getTenant().getId() : user.getId(), resolvePlan(user));

        String systemInstruction = 
            "You are a professional email marketing assistant and web designer. " +
            "Generate an email template with a subject and a fully formatted HTML body. " +
            "CRITICAL: The 'body' MUST be a complete, fully formed HTML5 document starting exactly with <!DOCTYPE html>. " +
            "It must include <html>, <head>, <style>, and <body> tags. Use email-client-safe markup, favoring table-based layouts and conservative inline CSS compatible with Gmail and Outlook. " +
            "You MUST strictly follow any color, theme, or layout instructions requested by the user. If they ask for specific color combinations, you MUST apply them. Do not ignore color requests. " +
            "CRITICAL: The HTML 'body' MUST include a footer section at the very bottom containing an unsubscribe link exactly like this: <a href=\"{{unsubscribe_link}}\">Unsubscribe</a>. " +
            "You must return the result as a valid JSON object with exactly two keys: 'subject' and 'body'.";
        
        String userMsg = String.format(
            "Design an email template based on the following instruction: \n\"%s\"\n\nFormat: {\"subject\": \"...\", \"body\": \"...\"}", 
            prompt
        );

        Map<String, String> fallback = Map.of(
            "subject", "Template for {{business.name}}",
            "body", "<!DOCTYPE html><html><body><h2>Welcome</h2><p>This is a fallback template.</p><br><br><footer><a href=\"{{unsubscribe_link}}\">Unsubscribe</a></footer></body></html>"
        );

        Map<String, String> result = callAiHelper(user, systemInstruction, userMsg, fallback, prompt);
        String htmlBody = result.getOrDefault("body", fallback.get("body"));
        
        // Backend Validation: Enforce unsubscribe link
        boolean hasUnsubscribe = htmlBody.contains("{{unsubscribe_link}}");
        if (!hasUnsubscribe) {
            htmlBody = htmlBody.replace("</body>", "<br><br><div style=\"text-align:center;font-size:12px;color:#888;\"><a href=\"{{unsubscribe_link}}\">Unsubscribe</a></div></body>");
            hasUnsubscribe = true; // We just injected it
        }

        // HTML Sanitization
        htmlBody = sanitizeHtml(htmlBody);

        return AiTemplateResponse.builder()
                .subject(result.getOrDefault("subject", fallback.get("subject")))
                .html(htmlBody)
                .hasUnsubscribeLink(hasUnsubscribe)
                .build();
    }

    private String sanitizeHtml(String html) {
        if (html == null) return "";
        return html.replaceAll("(?i)<script.*?>.*?</script>", "")
                   .replaceAll("(?i)on[a-z]+\\s*=\\s*['\"].*?['\"]", "") // remove event handlers like onclick="doSomething()"
                   .replaceAll("(?i)<iframe.*?>.*?</iframe>", "");
    }

    private Map<String, String> callAiHelper(User user, String systemInstruction, String userMsg, Map<String, String> fallback, String prompt) {
        if (chatLanguageModel == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "AI models are currently not configured. Please check back later."
            );
        }

        Response<AiMessage> responseObj = null;
        try {
            responseObj = chatLanguageModel.generate(List.of(
                SystemMessage.from(systemInstruction),
                UserMessage.from(userMsg)
            ));
        } catch (Exception ex) {
            log.warn("[AI Email Service] AI Server offline ({}) — returning fallback template.", ex.getMessage());
            return fallback;
        }
        
        String rawResponse = responseObj.content().text().trim();

        TokenUsage tokenUsage = responseObj.tokenUsage();
        if (tokenUsage != null) {
            int input = tokenUsage.inputTokenCount() != null ? tokenUsage.inputTokenCount() : 0;
            int output = tokenUsage.outputTokenCount() != null ? tokenUsage.outputTokenCount() : 0;
            tokenBudgetService.recordTokenUsage(user.getTenant().getId(), input, output);
            costTracker.trackCost(input, output, user.getTenant().getId());
        }

        try {
            String jsonText = rawResponse;
            if (jsonText.startsWith("```json")) jsonText = jsonText.substring(7);
            else if (jsonText.startsWith("```")) jsonText = jsonText.substring(3);
            if (jsonText.endsWith("```")) jsonText = jsonText.substring(0, jsonText.length() - 3);
            jsonText = jsonText.trim();

            try {
                com.fasterxml.jackson.databind.ObjectMapper lenientMapper = objectMapper.copy()
                        .configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_UNQUOTED_CONTROL_CHARS, true)
                        .configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_SINGLE_QUOTES, true);
                return lenientMapper.readValue(jsonText, Map.class);
            } catch (Exception parseEx) {
                log.warn("[CustomEmail] Standard JSON parse failed, attempting regex extraction from LLM response: {}", parseEx.getMessage());
                return parseJsonWithRegex(rawResponse, prompt);
            }
        } catch (Exception e) {
            log.error("Failed to parse AI email generation JSON: " + rawResponse, e);
            return fallback;
        }
    }

    private Map<String, String> parseJsonWithRegex(String text, String fallbackPrompt) {
        String subject = extractJsonField(text, "subject");
        String body = extractJsonField(text, "body");
        String ctaLabel = extractJsonField(text, "ctaLabel");
        String ctaUrl = extractJsonField(text, "ctaUrl");

        if (subject.isBlank()) subject = "Special Announcement from {{business.name}}";
        if (body.isBlank()) body = text.replace("\n", "<br>");
        if (ctaLabel.isBlank()) ctaLabel = "View Details";

        Map<String, String> res = new java.util.HashMap<>();
        res.put("subject", subject);
        res.put("body", body);
        res.put("ctaLabel", ctaLabel);
        res.put("ctaUrl", ctaUrl);
        return res;
    }

    private String extractJsonField(String text, String fieldName) {
        try {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("\"" + fieldName + "\"\\s*:\\s*\"(.*?)\"(?:\\s*[,}]|\n)", java.util.regex.Pattern.DOTALL);
            java.util.regex.Matcher m = p.matcher(text);
            if (m.find()) {
                return m.group(1).replace("\\n", "\n").replace("\\\"", "\"").trim();
            }
        } catch (Exception e) {
            // Ignore
        }
        return "";
    }

    private boolean isAdmin(User user) {
        return user != null && (user.getRole() == User.Role.ADMIN || user.getRole() == User.Role.OWNER || user.getRole() == User.Role.AGENT);
    }

    @Transactional
    public CustomEmailDTO saveDraft(User owner, CustomEmailRequest req) {
        CustomEmail draft = CustomEmail.builder()
                .owner(owner)
                .name(req.getName() != null && !req.getName().isBlank() ? req.getName().trim() : req.getSubject().trim())
                .subject(req.getSubject().trim())
                .body(sanitise(req.getBody()))
                .ctaLabel(req.getCtaLabel())
                .ctaUrl(req.getCtaUrl())
                .recipientMode(req.getRecipientMode() != null ? req.getRecipientMode() : RecipientMode.ALL)
                .tagsFilter(req.getTagsFilter())
                .status(EmailStatus.DRAFT)
                .build();
        return toDTO(customEmailRepository.save(draft));
    }

    @Transactional
    public CustomEmail saveCampaign(User owner, CustomEmailRequest req) {
        CustomEmail campaign = CustomEmail.builder()
                .owner(owner)
                .name(req.getName() != null && !req.getName().isBlank() ? req.getName().trim() : req.getSubject().trim())
                .subject(req.getSubject().trim())
                .body(sanitise(req.getBody()))
                .ctaLabel(req.getCtaLabel())
                .ctaUrl(req.getCtaUrl())
                .recipientMode(req.getRecipientMode() != null ? req.getRecipientMode() : RecipientMode.ALL)
                .tagsFilter(req.getTagsFilter())
                .status(EmailStatus.DRAFT)
                .build();
        return customEmailRepository.save(campaign);
    }

    /**
     * Ownership verification helper ensuring the requested campaign belongs
     * to the authenticated actor's tenant. Prevents IDOR vulnerabilities.
     */
    private CustomEmail findOwnedCampaign(UUID campaignId, User actor) {
        if (campaignId == null) {
            throw new IllegalArgumentException("Campaign not found or access denied");
        }
        if (actor == null || actor.getTenant() == null || actor.getTenant().getId() == null) {
            throw new IllegalArgumentException("Campaign not found or access denied");
        }
        CustomEmail campaign = customEmailRepository.findById(campaignId)
                .orElseThrow(() -> new IllegalArgumentException("Campaign not found or access denied"));

        if (campaign.getOwner() == null || campaign.getOwner().getTenant() == null || campaign.getOwner().getTenant().getId() == null) {
            throw new IllegalArgumentException("Campaign not found or access denied");
        }
        if (!campaign.getOwner().getTenant().getId().equals(actor.getTenant().getId())) {
            throw new IllegalArgumentException("Campaign not found or access denied");
        }
        return campaign;
    }

    /**
     * Dry Run / Test Send: Sends a rendered preview message to a test email.
     */
    @Transactional
    public String sendTestEmail(UUID campaignId, String testEmailAddress, User actor) {
        CustomEmail campaign = findOwnedCampaign(campaignId, actor);

        if (!isValidEmail(testEmailAddress)) {
            throw new IllegalArgumentException("Invalid test email address");
        }

        try {
            String businessName = actor.getBusinessName() != null ? actor.getBusinessName()
                    : (actor.getTenant() != null && actor.getTenant().getBusinessName() != null ? actor.getTenant().getBusinessName() : actor.getDisplayName());
            
            String trackingToken = trackingService.generateTrackingToken();
            String unsubUrl = trackingService.getUnsubscribeUrl(trackingToken);
            String testName = (actor.getDisplayName() != null && !actor.getDisplayName().isBlank()) ? actor.getDisplayName() : extractNameFromEmail(testEmailAddress);

            String subject = personaliseString("[TEST PREVIEW] " + campaign.getSubject(), testName, testEmailAddress, businessName, unsubUrl, campaign.getCtaLabel(), campaign.getCtaUrl());
            String body = personaliseString(campaign.getBody(), testName, testEmailAddress, businessName, unsubUrl, campaign.getCtaLabel(), campaign.getCtaUrl());

            sendOne(testEmailAddress, subject, body,
                    campaign.getCtaLabel(), campaign.getCtaUrl(), businessName,
                    trackingToken, actor.getTenant().getId(), campaignId);
            
            auditService.logAction(campaign, actor, EmailCampaignAuditLog.Action.TEST_SENT, "{\"testEmail\": \"" + testEmailAddress + "\"}");
            return "Sent successfully";
        } catch (Exception e) {
            log.error("Failed to send test email to {}", testEmailAddress, e);
            throw new RuntimeException("Failed to send test email: " + e.getMessage());
        }
    }

    public com.chatcrmlite.backend.dto.AudiencePreviewResponse previewAudience(User actor, com.chatcrmlite.backend.dto.AudiencePreviewRequest req) {
        String filterJson = "";
        if (req.getTagsFilter() != null) {
            if (req.getTagsFilter() instanceof String) {
                filterJson = (String) req.getTagsFilter();
            } else {
                try {
                    filterJson = objectMapper.writeValueAsString(req.getTagsFilter());
                } catch (Exception e) {
                    log.error("Failed to serialize tagsFilter", e);
                }
            }
        }
        
        String actualFilter = req.getRecipientMode() == CustomEmail.RecipientMode.MANUAL ? req.getManualRecipients() : filterJson;
        List<String> recipientsRaw = audienceResolver.resolveEmailAddresses(actor, req.getRecipientMode().name(), actualFilter);
        
        int validCount = 0;
        int skippedCount = 0;
        UUID tenantId = actor.getTenant().getId();
        
        for (String raw : recipientsRaw) {
            String email = raw;
            if (raw.contains("<") && raw.contains(">")) {
                int start = raw.indexOf("<");
                int end = raw.indexOf(">");
                if (end > start) {
                    email = raw.substring(start + 1, end).trim();
                }
            }
            String normalizedEmail = suppressionService.normalizeEmail(email);
            if (suppressionService.isSuppressed(tenantId, normalizedEmail)) {
                skippedCount++;
            } else {
                validCount++;
            }
        }
        
        return com.chatcrmlite.backend.dto.AudiencePreviewResponse.builder()
                .matched(recipientsRaw.size())
                .excluded(skippedCount)
                .eligible(validCount)
                .build();
    }

    @Transactional
    public CustomEmailDTO scheduleOrExecuteCampaign(UUID campaignId, CustomEmailRequest req, User actor) {
        CustomEmail campaign;
        if (campaignId != null) {
            campaign = findOwnedCampaign(campaignId, actor);
            // Update fields if provided in request
            if (req.getName() != null) campaign.setName(req.getName().trim());
            if (req.getSubject() != null) campaign.setSubject(req.getSubject().trim());
            if (req.getBody() != null) campaign.setBody(sanitise(req.getBody()));
            if (req.getCtaLabel() != null) campaign.setCtaLabel(req.getCtaLabel());
            if (req.getCtaUrl() != null) campaign.setCtaUrl(req.getCtaUrl());
            if (req.getRecipientMode() != null) campaign.setRecipientMode(req.getRecipientMode());
            if (req.getTagsFilter() != null) campaign.setTagsFilter(req.getTagsFilter());
        } else {
            campaign = saveCampaign(actor, req);
        }

        if (campaign.getStatus() == EmailStatus.SENDING || campaign.getStatus() == EmailStatus.COMPLETED) {
            throw new IllegalStateException("Campaign is already in status: " + campaign.getStatus());
        }

        // 1. Resolve and freeze immutable audience snapshot
        resolveAndFreezeAudience(campaign, req.getManualRecipients());

        // 2. Schedule or immediate execute
        if (req.getScheduledAt() != null && req.getScheduledAt().isAfter(LocalDateTime.now())) {
            campaign.setScheduledAt(req.getScheduledAt());
            stateService.transitionState(campaign, EmailStatus.SCHEDULED, actor);

            String tenantTz = (actor.getTenant() != null && actor.getTenant().getTimezone() != null)
                    ? actor.getTenant().getTimezone()
                    : "Asia/Kolkata";
            ZoneId zoneId;
            try {
                zoneId = ZoneId.of(tenantTz);
            } catch (Exception e) {
                zoneId = ZoneId.of("Asia/Kolkata");
            }

            Date startDate = Date.from(req.getScheduledAt().atZone(zoneId).toInstant());
            distributedSchedulerService.scheduleOneTimeJob(
                    "email-campaign-job-" + campaign.getId(),
                    com.chatcrmlite.backend.services.email.EmailCampaignJob.class,
                    startDate,
                    Map.of("campaignId", campaign.getId().toString())
            );
        } else {
            stateService.transitionState(campaign, EmailStatus.SENDING, actor);
            startCampaignExecution(campaign.getId());
        }
        
        return toDTO(customEmailRepository.save(campaign));
    }

    private void resolveAndFreezeAudience(CustomEmail campaign, String manualRecipients) {
        log.info("[EmailAudienceResolver] Resolving immutable audience snapshot for campaignId={}", campaign.getId());

        String filterJson = campaign.getRecipientMode() == RecipientMode.MANUAL ? manualRecipients : campaign.getTagsFilter();
        List<String> recipientsRaw = audienceResolver.resolveEmailAddresses(campaign.getOwner(), campaign.getRecipientMode().name(), filterJson);
        
        // Quota check
        quotaEnforcerService.verifyEmailCampaignQuota(campaign.getOwner().getTenant().getId(), recipientsRaw.size());

        int validCount = 0;
        int skippedCount = 0;

        List<EmailCampaignRecipient> recipientsToSave = new ArrayList<>();
        UUID tenantId = campaign.getOwner().getTenant().getId();

        for (String raw : recipientsRaw) {
            String email = raw;
            String name = "";

            if (raw.contains("<") && raw.contains(">")) {
                int start = raw.indexOf("<");
                int end = raw.indexOf(">");
                if (end > start) {
                    name = raw.substring(0, start).trim();
                    email = raw.substring(start + 1, end).trim();
                }
            }

            String normalizedEmail = suppressionService.normalizeEmail(email);

            if (suppressionService.isSuppressed(tenantId, normalizedEmail)) {
                skippedCount++;
                continue;
            }

            if (recipientRepository.existsByTenantIdAndCampaignIdAndEmail(tenantId, campaign.getId(), normalizedEmail)) {
                skippedCount++;
                continue;
            }

            EmailCampaignRecipient recipient = EmailCampaignRecipient.builder()
                    .campaignId(campaign.getId())
                    .email(normalizedEmail)
                    .trackingToken(UUID.randomUUID().toString())
                    .deliveryStatus(DeliveryStatus.PENDING)
                    .build();
            recipient.setTenantId(campaign.getOwner().getTenant().getId());
            recipientsToSave.add(recipient);
            validCount++;
        }

        recipientRepository.saveAll(recipientsToSave);

        // Create Snapshot
        EmailCampaignSnapshot snapshot = EmailCampaignSnapshot.builder()
                .campaignId(campaign.getId())
                .subject(campaign.getSubject())
                .body(campaign.getBody())
                .ctaLabel(campaign.getCtaLabel())
                .ctaUrl(campaign.getCtaUrl())
                .audienceType(campaign.getRecipientMode().name())
                .audienceFilterJson(filterJson)
                .build();
        snapshot.setTenant(campaign.getOwner().getTenant());
        snapshot = snapshotRepository.save(snapshot);

        campaign.setSnapshotId(snapshot.getId());
        campaign.setTotalRecipients(validCount);
        customEmailRepository.save(campaign);

        log.info("[EmailAudienceResolver] Frozen audience snapshot for campaignId={}: Valid={}, Skipped={}",
                campaign.getId(), validCount, skippedCount);
    }

    /**
     * Durable Batch Processor
     */
    @Async
    public void startCampaignExecution(UUID campaignId) {
        CustomEmail campaign = customEmailRepository.findById(campaignId)
                .orElseThrow(() -> new RuntimeException("Campaign not found: " + campaignId));

        if (campaign.getStatus() != EmailStatus.SENDING) {
            log.warn("[CustomEmail] Campaign {} is not in SENDING state, aborting execution", campaignId);
            return;
        }

        String businessName = campaign.getOwner().getBusinessName() != null
                ? campaign.getOwner().getBusinessName()
                : campaign.getOwner().getDisplayName() != null ? campaign.getOwner().getDisplayName() : campaign.getOwner().getEmail();
        UUID tenantId = campaign.getOwner().getTenant().getId();

        int batchSize = 100;
        int processedThisRun = 0;
        int sentThisRun = 0;
        int failedThisRun = 0;

        try {
            while (true) {
                // Refresh campaign to check state changes (like paused or cancelled)
                campaign = customEmailRepository.findById(campaignId).orElse(null);
                if (campaign == null || campaign.getStatus() != EmailStatus.SENDING) {
                    log.info("[CustomEmail] Campaign {} execution halted. Current status: {}", campaignId, campaign != null ? campaign.getStatus() : "DELETED");
                    break;
                }

                Page<EmailCampaignRecipient> pendingBatch = recipientRepository.findByCampaignIdAndDeliveryStatus(
                        campaignId, DeliveryStatus.PENDING, PageRequest.of(0, batchSize));

                if (pendingBatch.isEmpty()) {
                    log.info("[CustomEmail] Campaign {} execution finished. No pending recipients left.", campaignId);
                    stateService.transitionState(campaign, EmailStatus.COMPLETED, null);
                    break;
                }

                for (EmailCampaignRecipient recipient : pendingBatch) {
                    try {
                        String trackingToken = trackingService.generateTrackingToken();
                        recipient.setTrackingToken(trackingToken);

                        Optional<Contact> cOpt = contactRepository.findFirstByEmailAndTenant_Id(recipient.getEmail(), tenantId);
                        String name = cOpt.map(Contact::getName).filter(n -> n != null && !n.isBlank())
                                .orElseGet(() -> extractNameFromEmail(recipient.getEmail()));

                        String unsubUrl = trackingService.getUnsubscribeUrl(trackingToken);

                        String subject = personaliseString(campaign.getSubject(), name, recipient.getEmail(), businessName, unsubUrl, campaign.getCtaLabel(), campaign.getCtaUrl());
                        String body = personaliseString(campaign.getBody(), name, recipient.getEmail(), businessName, unsubUrl, campaign.getCtaLabel(), campaign.getCtaUrl());

                        sendOne(recipient.getEmail(), subject, body,
                                campaign.getCtaLabel(), campaign.getCtaUrl(), businessName,
                                trackingToken, tenantId, campaignId);

                        recipient.setDeliveryStatus(DeliveryStatus.SENT);
                        recipient.setSentAt(LocalDateTime.now());
                        sentThisRun++;
                    } catch (Exception ex) {
                        log.error("[CustomEmail] SMTP failed to send to {} — {}", recipient.getEmail(), ex.getMessage());
                        recipient.setDeliveryStatus(DeliveryStatus.FAILED);
                        recipient.setFailedAt(LocalDateTime.now());
                        recipient.setFailureMessage(ex.getMessage());
                        failedThisRun++;
                    }
                    processedThisRun++;
                }

                // Batch save and checkpoint
                recipientRepository.saveAll(pendingBatch);
                
                campaign.setProcessedRecipients(campaign.getProcessedRecipients() + pendingBatch.getNumberOfElements());
                campaign.setTotalSent(campaign.getTotalSent() + sentThisRun);
                campaign.setTotalFailed(campaign.getTotalFailed() + failedThisRun);
                customEmailRepository.save(campaign);
                
                sentThisRun = 0;
                failedThisRun = 0;
            }
        } catch (Exception e) {
            log.error("[CustomEmail] Critical error in campaign {} execution", campaignId, e);
            stateService.transitionState(campaign, EmailStatus.FAILED, null);
        }
    }

    @Transactional
    public CustomEmailDTO pauseCampaign(UUID campaignId, User actor) {
        CustomEmail campaign = findOwnedCampaign(campaignId, actor);
        stateService.transitionState(campaign, EmailStatus.PAUSED, actor);
        return toDTO(campaign);
    }

    @Transactional
    public CustomEmailDTO resumeCampaign(UUID campaignId, User actor) {
        CustomEmail campaign = findOwnedCampaign(campaignId, actor);
        stateService.transitionState(campaign, EmailStatus.SENDING, actor);
        startCampaignExecution(campaignId);
        return toDTO(campaign);
    }

    @Transactional
    public CustomEmailDTO cancelCampaign(UUID campaignId, User actor) {
        CustomEmail campaign = findOwnedCampaign(campaignId, actor);
        stateService.transitionState(campaign, EmailStatus.CANCELLED, actor);
        
        // Mark remaining pending as cancelled
        List<EmailCampaignRecipient> pending = recipientRepository.findByCampaignIdAndDeliveryStatus(
            campaignId, DeliveryStatus.PENDING, Pageable.unpaged()).getContent();
            
        for(EmailCampaignRecipient r : pending) {
            r.setDeliveryStatus(DeliveryStatus.FAILED); // or CANCELLED if we added it, but let's use FAILED with reason
            r.setFailureMessage("Campaign Cancelled");
        }
        recipientRepository.saveAll(pending);
        
        return toDTO(campaign);
    }

    @Transactional(readOnly = true)
    public Page<CustomEmailDTO> getHistory(User owner, Pageable pageable) {
        if (isAdmin(owner)) {
            if (owner.getTenant() != null) {
                return customEmailRepository.findByTenantIdOrderByCreatedAtDesc(owner.getTenant().getId(), pageable).map(this::toDTO);
            }
            return customEmailRepository.findAllByOrderByCreatedAtDesc(pageable).map(this::toDTO);
        }
        return customEmailRepository
                .findAllByOwnerOrderByCreatedAtDesc(owner, pageable)
                .map(this::toDTO);
    }

    @Transactional(readOnly = true)
    public CustomEmailDTO getById(UUID id, User owner) {
        CustomEmail campaign = findOwnedCampaign(id, owner);
        return toDTO(campaign);
    }

    private void sendOne(String to, String subject, String body,
                         String ctaLabel, String ctaUrl, String businessName,
                         String trackingToken, UUID tenantId, UUID campaignId) throws Exception {
        
        com.chatcrmlite.backend.models.Tenant tenant = tenantRepository.findById(tenantId).orElse(null);
        String html;

        String trimmedBody = body != null ? body.trim() : "";
        boolean isRawHtml = trimmedBody.startsWith("<!DOCTYPE html>") 
                || trimmedBody.startsWith("<html") 
                || trimmedBody.startsWith("<div") 
                || trimmedBody.startsWith("<table") 
                || trimmedBody.startsWith("<head") 
                || trimmedBody.startsWith("<body");

        // If the body is a raw HTML template from the Enterprise Builder, bypass Thymeleaf
        if (isRawHtml) {
            html = body;
            // Inject CTA button if defined but missing from raw HTML
            if (ctaUrl != null && !ctaUrl.isBlank() && !html.contains(ctaUrl)) {
                String btnText = (ctaLabel != null && !ctaLabel.isBlank()) ? ctaLabel : "View Offer";
                String btnColor = (tenant != null && tenant.getPrimaryColor() != null && !tenant.getPrimaryColor().isBlank())
                        ? tenant.getPrimaryColor() : "#2563EB";
                String btnHtml = "<div style=\"text-align: center; margin: 32px 0;\">"
                        + "<a href=\"" + ctaUrl + "\" style=\"display: inline-block; padding: 14px 32px; color: #FFFFFF; font-weight: 700; text-decoration: none; border-radius: 8px; background-color: " + btnColor + "; box-shadow: 0 4px 12px rgba(0,0,0,0.15);\">"
                        + btnText + "</a></div>";
                
                if (html.contains("</body>")) {
                    html = html.replace("</body>", btnHtml + "</body>");
                } else {
                    html = html + btnHtml;
                }
            }
        } else {
            // Use the marketing-specific custom-email template and inject brand variables
            Context ctx = new Context();
            ctx.setVariable("subject",      subject);
            ctx.setVariable("body",         body);
            ctx.setVariable("ctaLabel",     ctaLabel);
            ctx.setVariable("ctaUrl",       ctaUrl);
            ctx.setVariable("businessName", businessName);
            
            if (tenant != null) {
                ctx.setVariable("logoUrl", tenant.getLogoUrl());
                ctx.setVariable("primaryColor", tenant.getPrimaryColor());
                ctx.setVariable("businessAddress", tenant.getAddress());
            }
            
            html = templateEngine.process("email/custom-email", ctx);
        }
        
        html = trackingService.rewriteLinks(html, tenantId, campaignId, trackingToken);
        html = trackingService.injectTrackingPixel(html, trackingToken);
        html = trackingService.appendUnsubscribeFooter(html, trackingToken);

        com.chatcrmlite.backend.models.EmailProvider defaultProvider = emailProviderService
                .getDefaultProvider(tenantId.toString())
                .orElse(null);

        if (defaultProvider != null) {
            com.chatcrmlite.backend.services.email.EmailSenderProvider providerInstance = providerFactory.getProvider(defaultProvider);
            com.chatcrmlite.backend.services.email.EmailRequest request = com.chatcrmlite.backend.services.email.EmailRequest.builder()
                    .toEmail(to)
                    .subject(subject)
                    .htmlBody(html)
                    .build();
            providerInstance.sendBatch(List.of(request), defaultProvider.getFromEmail());
            log.info("[CustomEmail] Sent campaign email to {} via default BYOP Provider '{}' ({})", to, defaultProvider.getName(), defaultProvider.getProviderType());
        } else {
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, "UTF-8");
            helper.setFrom(new jakarta.mail.internet.InternetAddress(fromAddress, "GyanVaniAi", "UTF-8"));
            helper.setReplyTo(new jakarta.mail.internet.InternetAddress(fromAddress, "GyanVaniAi", "UTF-8"));
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            
            // Add List-Unsubscribe headers
            String unsubUrl = trackingService.getUnsubscribeUrl(trackingToken);
            mime.addHeader("List-Unsubscribe", "<" + unsubUrl + ">");
            mime.addHeader("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");

            mailSender.send(mime);
            log.debug("[CustomEmail] Sent '{}' → {} via default system mailer", subject, to);
        }
    }

    private String sanitise(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        boolean isHtmlDocument = trimmed.startsWith("<!DOCTYPE html>") 
                || trimmed.startsWith("<html") 
                || trimmed.startsWith("<div") 
                || trimmed.startsWith("<table") 
                || trimmed.startsWith("<head") 
                || trimmed.startsWith("<body");

        // Strip dangerous script/iframe tags while preserving valid HTML & CSS
        String clean = raw
                .replace("<script", "&lt;script")
                .replace("</script", "&lt;/script")
                .replace("<iframe", "&lt;iframe")
                .replace("</iframe", "&lt;/iframe")
                .replace("javascript:", "");

        // Only convert newlines to <br> for plain-text / markdown inputs, NEVER for HTML documents!
        if (!isHtmlDocument) {
            clean = clean.replace("\r\n", "<br>").replace("\n", "<br>");
        }
        return clean;
    }

    private boolean isValidEmail(String email) {
        return email != null && email.contains("@") && email.contains(".");
    }

    private String personaliseString(String input, String recipientName, String recipientEmail, String businessName, String unsubUrl, String ctaLabel, String ctaUrl) {
        if (input == null) return "";
        
        String name = (recipientName != null && !recipientName.isBlank()) ? recipientName.trim() : "Valued Customer";
        String email = (recipientEmail != null && !recipientEmail.isBlank()) ? recipientEmail.trim() : "";
        String bName = (businessName != null && !businessName.isBlank()) ? businessName.trim() : "Our Business";
        String dateStr = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("MMMM dd, yyyy"));
        String uUrl = (unsubUrl != null && !unsubUrl.isBlank()) ? unsubUrl : "#";
        String cLabel = (ctaLabel != null && !ctaLabel.isBlank()) ? ctaLabel.trim() : "Click Here";
        String cUrl = (ctaUrl != null && !ctaUrl.isBlank()) ? ctaUrl.trim() : "#";

        return input
            .replace("{{lead.name}}", name)
            .replace("{{lead.email}}", email)
            .replace("[User Name]", name)
            .replace("[Customer Name]", name)
            .replace("[Recipient Name]", name)
            .replace("[Name]", name)
            .replace("{{name}}", name)
            .replace("{{Name}}", name)
            .replace("{name}", name)
            .replace("{Name}", name)
            .replace("{{business.name}}", bName)
            .replace("{{businessName}}", bName)
            .replace("{{tenant.businessName}}", bName)
            .replace("[Business Name]", bName)
            .replace("[Company]", bName)
            .replace("{{current_date}}", dateStr)
            .replace("{{unsubscribe_link}}", uUrl)
            .replace("{{ctaLabel}}", cLabel)
            .replace("{{ctaUrl}}", cUrl);
    }

    private String extractNameFromEmail(String email) {
        if (email == null || !email.contains("@")) return "Valued Customer";
        String prefix = email.split("@")[0];
        String clean = prefix.replaceAll("[._\\-+1234567890]", " ").trim();
        if (clean.isBlank()) return "Valued Customer";
        String[] parts = clean.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (!p.isBlank()) {
                sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1).toLowerCase()).append(" ");
            }
        }
        return sb.toString().trim();
    }

    public CustomEmailDTO toDTO(CustomEmail e) {
        long sentCount = 0;
        long uniqueOpens = 0;
        long uniqueClicks = 0;
        long bounces = 0;
        long unsubscribes = 0;
        
        double openRate = 0;
        double clickRate = 0;
        double clickToOpenRate = 0;
        double bounceRate = 0;
        double unsubscribeRate = 0;

        if (e.getId() != null) {
            sentCount = recipientRepository.countByCampaignIdAndDeliveryStatusIn(
                e.getId(), 
                Arrays.asList(DeliveryStatus.SENT, DeliveryStatus.DELIVERED, DeliveryStatus.BOUNCED)
            );
            uniqueOpens = recipientRepository.countByCampaignIdAndFirstOpenedAtIsNotNull(e.getId());
            uniqueClicks = recipientRepository.countByCampaignIdAndFirstClickedAtIsNotNull(e.getId());
            bounces = recipientRepository.countByCampaignIdAndDeliveryStatus(e.getId(), DeliveryStatus.BOUNCED);
            unsubscribes = recipientRepository.countByCampaignIdAndUnsubscribedAtIsNotNull(e.getId());

            if (sentCount > 0) {
                openRate = (double) uniqueOpens / sentCount * 100.0;
                clickRate = (double) uniqueClicks / sentCount * 100.0;
                bounceRate = (double) bounces / sentCount * 100.0;
                
                long sentOrDelivered = sentCount; 
                if (sentOrDelivered > 0) {
                    unsubscribeRate = (double) unsubscribes / sentOrDelivered * 100.0;
                }
            }
            if (uniqueOpens > 0) {
                clickToOpenRate = (double) uniqueClicks / uniqueOpens * 100.0;
            }
        }

        return CustomEmailDTO.builder()
                .id(e.getId())
                .name(e.getName())
                .subject(e.getSubject())
                .body(e.getBody())
                .ctaLabel(e.getCtaLabel())
                .ctaUrl(e.getCtaUrl())
                .recipientMode(e.getRecipientMode())
                .tagsFilter(e.getTagsFilter())
                .status(e.getStatus())
                .scheduledAt(e.getScheduledAt())
                .startedAt(e.getStartedAt())
                .completedAt(e.getCompletedAt())
                .pausedAt(e.getPausedAt())
                .cancelledAt(e.getCancelledAt())
                .totalRecipients(e.getTotalRecipients())
                .processedRecipients(e.getProcessedRecipients())
                .sentAt(e.getSentAt())
                .totalSent(e.getTotalSent())
                .totalFailed(e.getTotalFailed())
                .uniqueOpens(uniqueOpens)
                .uniqueClicks(uniqueClicks)
                .bounces(bounces)
                .unsubscribes(unsubscribes)
                .openRate(Math.round(openRate * 100.0) / 100.0)
                .clickRate(Math.round(clickRate * 100.0) / 100.0)
                .clickToOpenRate(Math.round(clickToOpenRate * 100.0) / 100.0)
                .bounceRate(Math.round(bounceRate * 100.0) / 100.0)
                .unsubscribeRate(Math.round(unsubscribeRate * 100.0) / 100.0)
                .createdAt(e.getCreatedAt())
                .build();
    }
}
