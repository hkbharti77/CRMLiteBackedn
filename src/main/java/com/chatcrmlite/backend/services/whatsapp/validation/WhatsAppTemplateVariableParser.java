package com.chatcrmlite.backend.services.whatsapp.validation;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enterprise Variable Parser for WhatsApp Message Templates.
 *
 * Responsibilities:
 * - Extracts and sorts variable indices ({{1}}, {{2}}, ...)
 * - Enforces Meta's sequential variable numbering rules
 * - Reconciles user-supplied sample values with realistic semantic fallbacks
 * - Asserts final sample count matches detected variable count exactly
 */
@Component
public class WhatsAppTemplateVariableParser {

    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{(\\d+)\\}\\}");

    private static final List<String> SEMANTIC_FALLBACKS = List.of(
            "Customer Name",
            "Order Number",
            "Company Name",
            "Tomorrow 3:00 PM",
            "Special Promo",
            "Support Team"
    );

    /**
     * Extracts unique, sorted variable numbers found in the text.
     * E.g. "Hello {{1}}, order {{2}} and {{1}}" -> [1, 2]
     */
    public List<Integer> extractVariables(String text) {
        if (!StringUtils.hasText(text)) {
            return Collections.emptyList();
        }
        Set<Integer> uniqueIndices = new TreeSet<>();
        Matcher matcher = VARIABLE_PATTERN.matcher(text);
        while (matcher.find()) {
            try {
                uniqueIndices.add(Integer.parseInt(matcher.group(1)));
            } catch (NumberFormatException ignored) {}
        }
        return new ArrayList<>(uniqueIndices);
    }

    /**
     * Validates that variables start at {{1}} and have no sequential gaps.
     * Throws IllegalArgumentException if validation fails.
     */
    public void validateSequential(List<Integer> indices, String context) {
        if (indices == null || indices.isEmpty()) {
            return;
        }
        if (indices.get(0) != 1) {
            throw new IllegalArgumentException(
                    String.format("%s variables must start at {{1}}, found {{%d}}.", context, indices.get(0)));
        }
        for (int i = 0; i < indices.size(); i++) {
            int expected = i + 1;
            int actual = indices.get(i);
            if (actual != expected) {
                throw new IllegalArgumentException(
                        String.format("%s variables must be sequential. Expected {{%d}}, but found gap before {{%d}}.",
                                context, expected, actual));
            }
        }
    }

    /**
     * Resolves and validates samples for all detected variables:
     * 1. Extracts variables and validates sequential ordering.
     * 2. Takes user-provided samples for available indices.
     * 3. For any missing/blank index, generates a high-quality semantic fallback.
     * 4. Asserts that final sample list matches detected variable count exactly.
     */
    public List<String> resolveAndValidateSamples(String context, String text, List<String> userSamples) {
        List<Integer> detected = extractVariables(text);
        if (detected.isEmpty()) {
            return Collections.emptyList();
        }

        validateSequential(detected, context);

        List<String> finalSamples = new ArrayList<>(detected.size());
        for (int i = 0; i < detected.size(); i++) {
            int varNum = detected.get(i);
            String sampleVal = null;
            if (userSamples != null && userSamples.size() >= varNum) {
                String candidate = userSamples.get(varNum - 1);
                if (StringUtils.hasText(candidate)) {
                    sampleVal = candidate.trim();
                }
            }
            if (sampleVal == null) {
                // Semantic fallback based on variable position
                if (i < SEMANTIC_FALLBACKS.size()) {
                    sampleVal = SEMANTIC_FALLBACKS.get(i);
                } else {
                    sampleVal = "Sample " + varNum;
                }
            }
            finalSamples.add(sampleVal);
        }

        if (finalSamples.size() != detected.size()) {
            throw new IllegalStateException(
                    String.format("Internal Error: %s sample count (%d) does not match detected variables (%d).",
                            context, finalSamples.size(), detected.size()));
        }

        return finalSamples;
    }

    /**
     * Replaces variable tokens with sample values for realistic live preview rendering
     * without modifying the underlying template string.
     */
    public String renderTemplatePreview(String text, List<String> samples) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String rendered = text;
        if (samples != null) {
            for (int i = 0; i < samples.size(); i++) {
                rendered = rendered.replace("{{" + (i + 1) + "}}", samples.get(i));
            }
        }
        return rendered;
    }
}
