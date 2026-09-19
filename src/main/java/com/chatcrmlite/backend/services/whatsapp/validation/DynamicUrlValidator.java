package com.chatcrmlite.backend.services.whatsapp.validation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enterprise URL Validator for WhatsApp Template Buttons.
 *
 * Enforces Meta Cloud API rules for both Static and Dynamic Tracking URLs
 * through a configurable DynamicUrlRules model.
 */
@Component
public class DynamicUrlValidator {

    private static final Pattern VARIABLE_FINDER = Pattern.compile("\\{\\{(\\d+)\\}\\}");

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DynamicUrlRules {
        @Builder.Default
        private int maxVariables = 1;

        @Builder.Default
        private String allowedVariablePattern = "\\{\\{1\\}\\}";

        @Builder.Default
        private boolean variableMustBeAtEnd = true;

        @Builder.Default
        private List<String> allowedProtocols = List.of("http://", "https://");
    }

    private final DynamicUrlRules rules;

    public DynamicUrlValidator() {
        this.rules = new DynamicUrlRules();
    }

    public DynamicUrlValidator(DynamicUrlRules customRules) {
        this.rules = (customRules != null) ? customRules : new DynamicUrlRules();
    }

    public boolean isDynamicUrl(String url) {
        return StringUtils.hasText(url) && url.contains("{{");
    }

    /**
     * Validates a URL button configuration.
     *
     * @param rawUrl The URL string defined on the button (e.g. "https://example.com/c/{{1}}")
     * @param rawUrlSample The sample destination URL for Meta reviewer inspection (e.g. "https://example.com/c/order123")
     * @throws IllegalArgumentException if validation fails
     */
    public void validateUrl(String rawUrl, String rawUrlSample) {
        if (!StringUtils.hasText(rawUrl)) {
            throw new IllegalArgumentException("URL button destination cannot be empty.");
        }

        String trimmedUrl = rawUrl.trim();

        // 1. Protocol check
        boolean hasAllowedProtocol = rules.getAllowedProtocols().stream()
                .anyMatch(p -> trimmedUrl.toLowerCase().startsWith(p));
        if (!hasAllowedProtocol) {
            throw new IllegalArgumentException(
                    "URL must start with an allowed protocol: " + String.join(", ", rules.getAllowedProtocols()));
        }

        if (isDynamicUrl(trimmedUrl)) {
            // Dynamic URL validation
            Matcher matcher = VARIABLE_FINDER.matcher(trimmedUrl);
            int varCount = 0;
            while (matcher.find()) {
                varCount++;
                String token = matcher.group(0);
                if (!token.equals("{{1}}")) {
                    throw new IllegalArgumentException(
                            "Meta URL buttons only support {{1}} as a variable, found " + token);
                }
            }

            if (varCount > rules.getMaxVariables()) {
                throw new IllegalArgumentException(
                        String.format("URL exceeds maximum allowed variables (%d). Found %d variables.",
                                rules.getMaxVariables(), varCount));
            }

            if (rules.isVariableMustBeAtEnd()) {
                // Meta guideline: variable should be placed at the end of the URL or as a final query param
                int varIndex = trimmedUrl.indexOf("{{1}}");
                String afterVar = trimmedUrl.substring(varIndex + 5);
                if (!afterVar.isBlank() && !afterVar.startsWith("/") && !afterVar.startsWith("&")) {
                    throw new IllegalArgumentException(
                            "In dynamic URLs, {{1}} should be positioned at the path or parameter boundary.");
                }
            }

            // Meta mandatory sample validation
            if (!StringUtils.hasText(rawUrlSample)) {
                throw new IllegalArgumentException(
                        "Dynamic URLs require a sample destination URL (urlSample) for Meta review verification.");
            }

            String trimmedSample = rawUrlSample.trim();
            if (trimmedSample.contains("{{")) {
                throw new IllegalArgumentException(
                        "Sample destination URL cannot contain variable tokens: " + trimmedSample);
            }

            try {
                URI sampleUri = URI.create(trimmedSample);
                if (sampleUri.getScheme() == null || sampleUri.getHost() == null) {
                    throw new IllegalArgumentException("Sample destination URL is not a valid absolute URL: " + trimmedSample);
                }
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid sample destination URL: " + e.getMessage());
            }

        } else {
            // Static URL validation
            try {
                URI uri = URI.create(trimmedUrl);
                if (uri.getScheme() == null || uri.getHost() == null) {
                    throw new IllegalArgumentException("Static URL is not a valid absolute URL: " + trimmedUrl);
                }
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid static URL format: " + e.getMessage());
            }
        }
    }

    /**
     * Validates all URL buttons in a template: enforces max count and per-button URL rules.
     */
    public void validateUrlButtons(List<com.chatcrmlite.backend.dto.WhatsAppTemplateDto.TemplateButtonDto> buttons) {
        if (buttons == null || buttons.isEmpty()) {
            return;
        }
        long urlCount = buttons.stream()
                .filter(b -> b != null && "URL".equalsIgnoreCase(b.getType()))
                .count();
        if (urlCount > 2) {
            throw new IllegalArgumentException("Maximum of 2 URL buttons are allowed per template. Found " + urlCount);
        }
        for (com.chatcrmlite.backend.dto.WhatsAppTemplateDto.TemplateButtonDto btn : buttons) {
            if (btn != null && "URL".equalsIgnoreCase(btn.getType())) {
                validateUrl(btn.getUrl(), btn.getUrlSample());
            }
        }
    }
}

