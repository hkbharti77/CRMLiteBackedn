package com.chatcrmlite.backend.services.email;

import com.chatcrmlite.backend.security.TenantContext;
import com.chatcrmlite.backend.dto.email.InboundEmailDTO;
import com.chatcrmlite.backend.models.email.EmailCampaignRecipient;
import com.chatcrmlite.backend.models.email.EmailInboundMessage;
import com.chatcrmlite.backend.models.email.EmailInboundMessage.AttributionStatus;
import com.chatcrmlite.backend.repositories.email.EmailCampaignRecipientRepository;
import com.chatcrmlite.backend.repositories.email.EmailInboundMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
@RequiredArgsConstructor
public class EmailInboundReplyService {

    private final EmailCampaignRecipientRepository recipientRepository;
    private final EmailInboundMessageRepository inboundMessageRepository;

    private static final Pattern REPLY_TOKEN_PATTERN = Pattern.compile("reply\\+([A-Za-z0-9_-]+)@", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCRIPT_PATTERN = Pattern.compile("<script[^>]*>.*?</script>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern IFRAME_PATTERN = Pattern.compile("<iframe[^>]*>.*?</iframe>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ON_EVENT_PATTERN = Pattern.compile("\\s+on[a-z]+\\s*=\\s*['\"][^'\"]*['\"]", Pattern.CASE_INSENSITIVE);
    private static final Pattern JAVASCRIPT_URL_PATTERN = Pattern.compile("javascript:[^\"'\\s>]+", Pattern.CASE_INSENSITIVE);

    /**
     * Ingest and process an incoming email reply.
     * Guaranteed idempotent by database unique constraint on (provider, providerMessageId).
     */
    @Transactional
    public EmailInboundMessage processInboundReply(InboundEmailDTO dto) {
        if (dto.getProvider() == null || dto.getProviderMessageId() == null) {
            log.warn("[InboundReply] Missing provider or providerMessageId in DTO");
            throw new IllegalArgumentException("Provider and providerMessageId are required");
        }

        // 1. Check if message was already saved (idempotency check)
        Optional<EmailInboundMessage> existing = inboundMessageRepository.findByProviderAndProviderMessageId(
                dto.getProvider(), dto.getProviderMessageId());
        if (existing.isPresent()) {
            log.info("[InboundReply] Duplicate inbound message ignored (provider={}, msgId={})",
                    dto.getProvider(), dto.getProviderMessageId());
            return existing.get();
        }

        // 2. Strict Zero-Guessing Attribution Hierarchy
        String tokenToSearch = extractReplyToken(dto);
        Optional<EmailCampaignRecipient> optRecipient = Optional.empty();

        if (tokenToSearch != null && !tokenToSearch.isBlank()) {
            optRecipient = recipientRepository.findByReplyToken(tokenToSearch);
        }

        if (optRecipient.isEmpty() && dto.getInReplyTo() != null && !dto.getInReplyTo().isBlank()) {
            String cleanMessageId = sanitizeHeaderMessageId(dto.getInReplyTo());
            optRecipient = recipientRepository.findByLastMessageId(cleanMessageId);
        }

        if (optRecipient.isEmpty() && dto.getReferences() != null && !dto.getReferences().isBlank()) {
            String[] refIds = dto.getReferences().split("\\s+");
            for (String refId : refIds) {
                String cleanRef = sanitizeHeaderMessageId(refId);
                if (!cleanRef.isBlank()) {
                    optRecipient = recipientRepository.findByLastMessageId(cleanRef);
                    if (optRecipient.isPresent()) break;
                }
            }
        }

        // 3. Prepare Inbound Message Entity
        EmailInboundMessage message = new EmailInboundMessage();
        message.setProvider(dto.getProvider());
        message.setProviderMessageId(dto.getProviderMessageId());
        message.setFromEmail(dto.getFromEmail());
        message.setToEmail(dto.getToEmail());
        message.setSubject(dto.getSubject());
        message.setInReplyTo(dto.getInReplyTo());
        message.setReferencesHeader(dto.getReferences());
        message.setReplyToken(tokenToSearch);

        String sanitizedHtml = sanitizeHtml(dto.getHtmlBody());
        String snippet = extractSnippet(dto.getTextBody(), sanitizedHtml);
        message.setHtmlBody(sanitizedHtml);
        message.setTextBody(dto.getTextBody());
        message.setReplySnippet(snippet);
        message.setReceivedAt(dto.getReceivedAt() != null ? dto.getReceivedAt() : Instant.now());

        EmailCampaignRecipient attributedRecipient = optRecipient.orElse(null);

        if (attributedRecipient != null) {
            message.setAttributionStatus(AttributionStatus.ATTRIBUTED);
            message.setTenantId(attributedRecipient.getTenantId());
            message.setCampaignRecipientId(attributedRecipient.getId());
            message.setCampaignId(attributedRecipient.getCampaignId());
        } else {
            message.setAttributionStatus(AttributionStatus.UNATTRIBUTED);
            message.setTenantId(null);
            message.setCampaignRecipientId(null);
            log.info("[InboundReply] Inbound email from {} could not be attributed to recipient", dto.getFromEmail());
        }

        // 4. Save to Database (Catch DB constraint violation for concurrent duplicate delivery)
        EmailInboundMessage savedMessage;
        try {
            savedMessage = inboundMessageRepository.saveAndFlush(message);
        } catch (DataIntegrityViolationException e) {
            log.warn("[InboundReply] Duplicate insertion race condition caught for provider={} msgId={}",
                    dto.getProvider(), dto.getProviderMessageId());
            return inboundMessageRepository.findByProviderAndProviderMessageId(dto.getProvider(), dto.getProviderMessageId())
                    .orElse(message);
        }

        // 5. Update Recipient Counters if Attributed
        if (savedMessage.getAttributionStatus() == AttributionStatus.ATTRIBUTED && attributedRecipient != null) {
            UUID currentTenant = TenantContext.getTenantId();
            try {
                if (attributedRecipient.getTenantId() != null) {
                    TenantContext.setTenantId(attributedRecipient.getTenantId());
                }
                recipientRepository.incrementReplyCountAtomic(
                        attributedRecipient.getId(),
                        attributedRecipient.getTenantId(),
                        savedMessage.getReceivedAt()
                );
                log.info("[InboundReply] Successfully incremented reply count for recipient={} campaign={}",
                        attributedRecipient.getId(), attributedRecipient.getCampaignId());
            } finally {
                TenantContext.setTenantId(currentTenant);
            }
        }

        return savedMessage;
    }

    private String extractReplyToken(InboundEmailDTO dto) {
        if (dto.getRecipientReplyToken() != null && !dto.getRecipientReplyToken().isBlank()) {
            return dto.getRecipientReplyToken().trim();
        }
        if (dto.getToEmail() != null) {
            Matcher matcher = REPLY_TOKEN_PATTERN.matcher(dto.getToEmail());
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    private String sanitizeHeaderMessageId(String headerValue) {
        if (headerValue == null) return "";
        String trimmed = headerValue.trim();
        if (trimmed.startsWith("<") && trimmed.endsWith(">")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    public String sanitizeHtml(String html) {
        if (html == null || html.isBlank()) return null;
        String clean = SCRIPT_PATTERN.matcher(html).replaceAll("");
        clean = IFRAME_PATTERN.matcher(clean).replaceAll("");
        clean = ON_EVENT_PATTERN.matcher(clean).replaceAll("");
        clean = JAVASCRIPT_URL_PATTERN.matcher(clean).replaceAll("");
        return clean;
    }

    public String extractSnippet(String text, String html) {
        String source = text;
        if (source == null || source.isBlank()) {
            if (html != null) {
                source = html.replaceAll("<[^>]*>", " ");
            }
        }
        if (source == null || source.isBlank()) return "";
        String cleaned = source.replaceAll("\\s+", " ").trim();
        if (cleaned.length() > 500) {
            return cleaned.substring(0, 497) + "...";
        }
        return cleaned;
    }
}
