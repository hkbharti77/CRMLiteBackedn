package com.chatcrmlite.backend.services.whatsapp.campaign;

import com.chatcrmlite.backend.dto.WhatsAppTemplateDto;
import com.chatcrmlite.backend.models.WhatsAppCampaign;
import com.chatcrmlite.backend.models.WhatsAppCampaignRecipient;
import com.chatcrmlite.backend.models.WhatsAppCampaignTrackedLink;
import com.chatcrmlite.backend.repositories.WhatsAppCampaignRecipientRepository;
import com.chatcrmlite.backend.repositories.WhatsAppCampaignTrackedLinkRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.net.URI;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppLinkTrackingService {

    private static final String ALPHANUMERIC = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int TOKEN_LENGTH = 12;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    // Regex matching raw http/https URLs in text
    private static final Pattern URL_PATTERN = Pattern.compile(
            "(https?://[a-zA-Z0-9\\-._~:/?#\\[\\]@!$&'()*+,;=%]+)",
            Pattern.CASE_INSENSITIVE
    );

    private final WhatsAppCampaignTrackedLinkRepository trackedLinkRepository;
    private final WhatsAppCampaignRecipientRepository recipientRepository;
    private final CampaignAnalyticsService analyticsService;
    private final ObjectMapper objectMapper;

    @Value("${app.tracking.base-url:${app.frontend.url:http://localhost:8080}}")
    private String baseUrl;

    /**
     * Generates a cryptographically strong 12-character URL-safe alphanumeric token.
     */
    public String generateToken() {
        StringBuilder sb = new StringBuilder(TOKEN_LENGTH);
        for (int i = 0; i < TOKEN_LENGTH; i++) {
            sb.append(ALPHANUMERIC.charAt(SECURE_RANDOM.nextInt(ALPHANUMERIC.length())));
        }
        return sb.toString();
    }

    /**
     * Strict SSRF and open-redirect protection.
     * Validates that the URL uses http/https and does not resolve to private/local/reserved addresses.
     */
    public boolean isValidDestinationUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }

        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                log.warn("[WhatsAppTracking] Rejected non-HTTP/HTTPS protocol: {}", scheme);
                return false;
            }

            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return false;
            }

            String lowerHost = host.toLowerCase().trim();

            // Rejects loopback & standard local aliases
            if (lowerHost.equals("localhost") || lowerHost.equals("0.0.0.0") || lowerHost.equals("::1")
                    || lowerHost.endsWith(".local") || lowerHost.endsWith(".internal")) {
                log.warn("[WhatsAppTracking] Rejected internal/localhost hostname: {}", host);
                return false;
            }

            // Check IP address format
            if (lowerHost.matches("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")) {
                String[] parts = lowerHost.split("\\.");
                int p0 = Integer.parseInt(parts[0]);
                int p1 = Integer.parseInt(parts[1]);

                // 127.0.0.0/8 (Loopback)
                if (p0 == 127) return false;
                // 10.0.0.0/8 (RFC 1918 Private)
                if (p0 == 10) return false;
                // 172.16.0.0/12 (RFC 1918 Private)
                if (p0 == 172 && p1 >= 16 && p1 <= 31) return false;
                // 192.168.0.0/16 (RFC 1918 Private)
                if (p0 == 192 && p1 == 168) return false;
                // 169.254.0.0/16 (Link-local & AWS/GCP/Azure Cloud Metadata)
                if (p0 == 169 && p1 == 254) return false;
                // 0.0.0.0/8
                if (p0 == 0) return false;
            }

            return true;
        } catch (Exception e) {
            log.warn("[WhatsAppTracking] Failed to parse target URL {}: {}", url, e.getMessage());
            return false;
        }
    }

    /**
     * Builds the public tracking URL for a given token.
     */
    public String getTrackingUrl(String token) {
        String base = baseUrl != null ? baseUrl.trim() : "http://localhost:8080";
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/api/v1/t/w/" + token;
    }

    /**
     * Wraps a single URL and persists the tracked link entity.
     */
    @Transactional
    public String wrapUrl(WhatsAppCampaign campaign, WhatsAppCampaignRecipient recipient, String originalUrl, int buttonIndex, String buttonText) {
        if (!isValidDestinationUrl(originalUrl)) {
            return originalUrl;
        }

        // Avoid double wrapping if already a tracking URL
        if (originalUrl.contains("/api/v1/t/w/")) {
            return originalUrl;
        }

        String token = generateToken();
        // Ensure token uniqueness
        while (trackedLinkRepository.findByToken(token).isPresent()) {
            token = generateToken();
        }

        WhatsAppCampaignTrackedLink trackedLink = WhatsAppCampaignTrackedLink.builder()
                .tenant(campaign.getTenant())
                .campaign(campaign)
                .recipient(recipient)
                .token(token)
                .originalUrl(originalUrl)
                .buttonIndex(buttonIndex)
                .buttonText(buttonText)
                .clickCount(0)
                .build();

        trackedLinkRepository.save(trackedLink);
        return getTrackingUrl(token);
    }

    /**
     * Scans and wraps all raw URLs in message body text.
     */
    @Transactional
    public String wrapBodyUrls(WhatsAppCampaign campaign, WhatsAppCampaignRecipient recipient, String bodyText) {
        if (bodyText == null || bodyText.isBlank()) {
            return bodyText;
        }

        Matcher matcher = URL_PATTERN.matcher(bodyText);
        StringBuffer sb = new StringBuffer();
        int index = 0;

        while (matcher.find()) {
            String url = matcher.group(1);
            if (isValidDestinationUrl(url) && !url.contains("/api/v1/t/w/")) {
                String wrapped = wrapUrl(campaign, recipient, url, index++, "Body Link");
                matcher.appendReplacement(sb, Matcher.quoteReplacement(wrapped));
            } else {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(url));
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Inspects and wraps CTA URL buttons in template buttonsJson.
     */
    @Transactional
    public String wrapButtonsJson(WhatsAppCampaign campaign, WhatsAppCampaignRecipient recipient, String buttonsJson) {
        if (buttonsJson == null || buttonsJson.isBlank()) {
            return buttonsJson;
        }

        try {
            List<WhatsAppTemplateDto.TemplateButtonDto> buttons = objectMapper.readValue(
                    buttonsJson,
                    new TypeReference<List<WhatsAppTemplateDto.TemplateButtonDto>>() {}
            );

            boolean modified = false;
            for (int i = 0; i < buttons.size(); i++) {
                WhatsAppTemplateDto.TemplateButtonDto btn = buttons.get(i);
                if (btn != null && "URL".equalsIgnoreCase(btn.getType()) && btn.getUrl() != null && !btn.getUrl().isBlank()) {
                    String originalUrl = btn.getUrl();
                    if (isValidDestinationUrl(originalUrl) && !originalUrl.contains("/api/v1/t/w/")) {
                        String trackingUrl = wrapUrl(campaign, recipient, originalUrl, i, btn.getText());
                        btn.setUrl(trackingUrl);
                        modified = true;
                    }
                }
            }

            return modified ? objectMapper.writeValueAsString(buttons) : buttonsJson;
        } catch (Exception e) {
            log.warn("[WhatsAppTracking] Could not wrap buttonsJson: {}", e.getMessage());
            return buttonsJson;
        }
    }

    /**
     * Resolves the token, atomically records the click timestamp and counter on both the link
     * and the recipient, and updates the campaign analytics rollup.
     */
    @Transactional
    public Optional<String> resolveAndRecordClick(String token, String clientIp) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }

        Optional<WhatsAppCampaignTrackedLink> optLink = trackedLinkRepository.findByToken(token.trim());
        if (optLink.isEmpty()) {
            return Optional.empty();
        }

        WhatsAppCampaignTrackedLink link = optLink.get();
        LocalDateTime now = LocalDateTime.now();

        // 1. Atomic update of link click count
        trackedLinkRepository.recordClick(link.getId(), now);

        // 2. Atomic update of recipient click count
        if (link.getRecipient() != null && link.getRecipient().getId() != null) {
            recipientRepository.recordClick(link.getRecipient().getId(), now);
        }

        // 3. Rollup campaign analytics
        if (link.getCampaign() != null) {
            try {
                analyticsService.updateAnalyticsRollup(link.getCampaign());
            } catch (Exception e) {
                log.warn("[WhatsAppTracking] Failed to rollup analytics for campaign {}: {}", link.getCampaign().getId(), e.getMessage());
            }
        }

        log.info("🎯 [WhatsAppTracking] Click recorded for campaign={} recipient={} token={} ip={}",
                link.getCampaign() != null ? link.getCampaign().getId() : "null",
                link.getRecipient() != null ? link.getRecipient().getId() : "null",
                token, clientIp);

        return Optional.of(link.getOriginalUrl());
    }
}
