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

    @Transactional
    public CustomEmailDTO send(User owner, CustomEmailRequest req) {
        List<String> recipients = resolveRecipients(owner, req);
        // Verify email quota
        quotaEnforcerService.verifyEmailCampaignQuota(owner.getTenant().getId(), recipients.size());

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
        CustomEmail saved = customEmailRepository.save(campaign);

        log.info("[CustomEmail] Campaign {} — {} recipients resolved for owner={}",
                saved.getId(), recipients.size(), owner.getId());

        dispatchAsync(saved.getId(), owner, recipients);

        return toDTO(saved);
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

    @Async
    public void dispatchAsync(UUID campaignId, User owner, List<String> recipients) {
        CustomEmail campaign = customEmailRepository.findById(campaignId)
                .orElseThrow(() -> new RuntimeException("Campaign not found: " + campaignId));

        String businessName = owner.getBusinessName() != null
                ? owner.getBusinessName()
                : owner.getDisplayName() != null ? owner.getDisplayName() : owner.getEmail();

        int sent   = 0;
        int failed = 0;

        for (String to : recipients) {
            try {
                sendOne(to, campaign.getSubject(), campaign.getBody(),
                        campaign.getCtaLabel(), campaign.getCtaUrl(), businessName);
                sent++;
            } catch (Exception e) {
                log.error("[CustomEmail] Failed to send to {} — {}", to, e.getMessage());
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
                    .map(Contact::getEmail)
                    .filter(e -> e != null && !e.isBlank() && isValidEmail(e))
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
                        .map(Contact::getEmail)
                        .filter(e -> e != null && !e.isBlank() && isValidEmail(e))
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
