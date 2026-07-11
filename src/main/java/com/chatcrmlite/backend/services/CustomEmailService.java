package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.dto.CustomEmailDTO;
import com.chatcrmlite.backend.dto.CustomEmailRequest;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.CustomEmail;
import com.chatcrmlite.backend.models.CustomEmail.EmailStatus;
import com.chatcrmlite.backend.models.CustomEmail.RecipientMode;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.CustomEmailRepository;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.time.LocalDateTime;
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
    private final JavaMailSender        mailSender;
    private final TemplateEngine        templateEngine;
    private final com.chatcrmlite.backend.services.tenant.QuotaEnforcerService quotaEnforcerService;

    @Value("${spring.mail.username}")
    private String fromAddress;

    @Autowired
    public CustomEmailService(CustomEmailRepository customEmailRepository,
                             ContactRepository contactRepository,
                             JavaMailSender mailSender,
                             TemplateEngine templateEngine,
                             com.chatcrmlite.backend.services.tenant.QuotaEnforcerService quotaEnforcerService) {
        this.customEmailRepository = customEmailRepository;
        this.contactRepository = contactRepository;
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
        this.quotaEnforcerService = quotaEnforcerService;
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
    private ObjectMapper objectMapper;

    public Map<String, String> generateAiContent(User user, String prompt) {
        if (user.getPlanType() == User.PlanType.FREE) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.PAYMENT_REQUIRED,
                "AI Email Generation is only available for PRO users and above."
            );
        }

        aiQuotaService.checkAndEnforceQuota(user.getTenant().getId(), user.getPlanType());

        if (chatLanguageModel == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "AI models are currently not configured. Please check back later."
            );
        }

        String systemInstruction = 
            "You are a professional email marketing assistant. " +
            "Generate email subject, email body, a call-to-action button label, and a call-to-action redirect URL based on the user's instructions. " +
            "If the user specifies or mentions a URL, website, or link in their instruction, extract and return it as 'ctaUrl'. Otherwise, return an empty string for 'ctaUrl'. " +
            "You must return the result as a valid JSON object with exactly four keys: 'subject', 'body', 'ctaLabel', and 'ctaUrl'. " +
            "Do not include markdown tags (like ```json), styling, formatting or extra text outside the JSON object.";
        
        String userMsg = String.format(
            "Write an email based on the following instruction: \n\"%s\"\n\nFormat: {\"subject\": \"...\", \"body\": \"...\", \"ctaLabel\": \"...\", \"ctaUrl\": \"...\"}", 
            prompt
        );

        Response<AiMessage> responseObj = chatLanguageModel.generate(List.of(
            SystemMessage.from(systemInstruction),
            UserMessage.from(userMsg)
        ));
        
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
            if (jsonText.startsWith("```json")) {
                jsonText = jsonText.substring(7);
            } else if (jsonText.startsWith("```")) {
                jsonText = jsonText.substring(3);
            }
            if (jsonText.endsWith("```")) {
                jsonText = jsonText.substring(0, jsonText.length() - 3);
            }
            jsonText = jsonText.trim();

            return objectMapper.readValue(jsonText, Map.class);
        } catch (Exception e) {
            log.error("Failed to parse AI email generation JSON: " + rawResponse, e);
            throw new RuntimeException("AI generated content in an invalid format. Please try again.");
        }
    }

    private boolean isAdmin(User user) {
        return user != null && (user.getRole() == User.Role.ADMIN || user.getRole() == User.Role.OWNER || user.getRole() == User.Role.AGENT);
    }

    @Transactional
    public CustomEmailDTO saveDraft(User owner, CustomEmailRequest req) {
        CustomEmail draft = CustomEmail.builder()
                .owner(owner)
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

    public CustomEmailDTO send(User owner, CustomEmailRequest req) {
        List<String> recipients = resolveRecipients(owner, req);
        // Verify email quota
        quotaEnforcerService.verifyEmailCampaignQuota(owner.getTenant().getId(), recipients.size());

        CustomEmail saved = saveCampaign(owner, req);

        log.info("[CustomEmail] Campaign {} — {} recipients resolved for owner={}",
                saved.getId(), recipients.size(), owner.getId());

        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                // Short sleep to ensure database transaction commits
                Thread.sleep(150);
                dispatchAsync(saved.getId(), owner, recipients);
            } catch (Exception e) {
                log.error("Error in async campaign dispatch", e);
            }
        });

        return toDTO(saved);
    }

    @Transactional
    public CustomEmail saveCampaign(User owner, CustomEmailRequest req) {
        CustomEmail campaign = CustomEmail.builder()
                .owner(owner)
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
        return customEmailRepository.findById(id)
                .filter(e -> e.getOwner().getTenant().getId().equals(owner.getTenant().getId()))
                .map(this::toDTO)
                .orElseThrow(() -> new RuntimeException("Campaign not found"));
    }

    @Transactional
    public void dispatchAsync(UUID campaignId, User owner, List<String> recipients) {
        CustomEmail campaign = customEmailRepository.findById(campaignId)
                .orElseThrow(() -> new RuntimeException("Campaign not found: " + campaignId));

        String businessName = owner.getBusinessName() != null
                ? owner.getBusinessName()
                : owner.getDisplayName() != null ? owner.getDisplayName() : owner.getEmail();

        int sent   = 0;
        int failed = 0;

        for (String recipientRaw : recipients) {
            try {
                String email = recipientRaw;
                String name = "";

                if (recipientRaw.contains("<") && recipientRaw.contains(">")) {
                    int start = recipientRaw.indexOf("<");
                    int end = recipientRaw.indexOf(">");
                    if (end > start) {
                        name = recipientRaw.substring(0, start).trim();
                        email = recipientRaw.substring(start + 1, end).trim();
                    }
                }

                // Personalise subject
                String subject = personaliseString(campaign.getSubject(), name);

                // Personalise body
                String body = personaliseString(campaign.getBody(), name);

                sendOne(email, subject, body,
                        campaign.getCtaLabel(), campaign.getCtaUrl(), businessName);
                sent++;
            } catch (Exception e) {
                log.error("[CustomEmail] Failed to send to {} — {}", recipientRaw, e.getMessage());
                failed++;
            }
        }

        campaign.setStatus(failed == recipients.size() && !recipients.isEmpty()
                ? EmailStatus.FAILED : EmailStatus.SENT);
        campaign.setSentAt(LocalDateTime.now());
        campaign.setTotalSent(sent);
        campaign.setTotalFailed(failed);
        customEmailRepository.save(campaign);

        log.info("[CustomEmail] Campaign {} complete — sent={} failed={}", campaignId, sent, failed);
    }

    private List<String> resolveRecipients(User owner, CustomEmailRequest req) {
        RecipientMode mode = req.getRecipientMode() != null ? req.getRecipientMode() : RecipientMode.ALL;

        return switch (mode) {
            case ALL -> contactRepository.findAllByOwner(owner).stream()
                    .filter(c -> c.getEmail() != null && !c.getEmail().isBlank() && isValidEmail(c.getEmail()))
                    .map(c -> c.getName() != null && !c.getName().isBlank() 
                            ? c.getName().trim() + " <" + c.getEmail().trim() + ">" 
                            : c.getEmail().trim())
                    .distinct()
                    .collect(Collectors.toList());

            case TAGGED -> {
                if (req.getTagsFilter() == null || req.getTagsFilter().isBlank()) {
                    yield List.of();
                }
                Set<String> filterTags = Arrays.stream(req.getTagsFilter().split(","))
                        .map(String::trim)
                        .filter(t -> !t.isEmpty())
                        .collect(Collectors.toSet());

                yield contactRepository.findAllByOwner(owner).stream()
                        .filter(c -> c.getTags() != null &&
                                c.getTags().stream().anyMatch(filterTags::contains))
                        .filter(c -> c.getEmail() != null && !c.getEmail().isBlank() && isValidEmail(c.getEmail()))
                        .map(c -> c.getName() != null && !c.getName().isBlank() 
                                ? c.getName().trim() + " <" + c.getEmail().trim() + ">" 
                                : c.getEmail().trim())
                        .distinct()
                        .collect(Collectors.toList());
            }

            case MANUAL -> {
                if (req.getManualRecipients() == null || req.getManualRecipients().isBlank()) {
                    yield List.of();
                }
                yield Arrays.stream(req.getManualRecipients().split(","))
                        .map(String::trim)
                        .filter(e -> !e.isEmpty() && isValidEmail(e))
                        .distinct()
                        .collect(Collectors.toList());
            }
        };
    }

    private void sendOne(String to, String subject, String body,
                         String ctaLabel, String ctaUrl, String businessName) throws Exception {
        Context ctx = new Context();
        ctx.setVariable("subject",      subject);
        ctx.setVariable("body",         body);
        ctx.setVariable("ctaLabel",     ctaLabel);
        ctx.setVariable("ctaUrl",       ctaUrl);
        ctx.setVariable("businessName", businessName);

        String html = templateEngine.process("email/custom-email", ctx);

        MimeMessage mime = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mime, "UTF-8");
        helper.setFrom(fromAddress);
        helper.setTo(to);
        helper.setSubject(subject);
        helper.setText(html, true);
        mailSender.send(mime);
        log.debug("[CustomEmail] Sent '{}' → {}", subject, to);
    }

    private String sanitise(String raw) {
        if (raw == null) return "";
        return raw
                .replace("&", "&amp;")
                .replace("<script", "&lt;script")
                .replace("</script", "&lt;/script")
                .replace("<iframe", "&lt;iframe")
                .replace("</iframe", "&lt;/iframe")
                .replace("javascript:", "")
                .replace("\r\n", "<br>")
                .replace("\n", "<br>");
    }

    private boolean isValidEmail(String email) {
        return email != null && email.contains("@") && email.contains(".");
    }

    private String personaliseString(String input, String name) {
        if (input == null) return "";
        String replacement = (name != null && !name.isBlank()) ? name.trim() : "";
        
        return input
            .replace("[User Name]", replacement)
            .replace("[Customer Name]", replacement)
            .replace("[Recipient Name]", replacement)
            .replace("[Name]", replacement)
            .replace("{{name}}", replacement)
            .replace("{{Name}}", replacement)
            .replace("{name}", replacement)
            .replace("{Name}", replacement);
    }

    public CustomEmailDTO toDTO(CustomEmail e) {
        return CustomEmailDTO.builder()
                .id(e.getId())
                .subject(e.getSubject())
                .body(e.getBody())
                .ctaLabel(e.getCtaLabel())
                .ctaUrl(e.getCtaUrl())
                .recipientMode(e.getRecipientMode())
                .tagsFilter(e.getTagsFilter())
                .status(e.getStatus())
                .sentAt(e.getSentAt())
                .totalSent(e.getTotalSent())
                .totalFailed(e.getTotalFailed())
                .createdAt(e.getCreatedAt())
                .build();
    }
}
