package com.chatcrmlite.backend.governance;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Arrays;

/**
 * Enforces safety guardrails for AI interactions.
 */
@Slf4j
@Service
public class ModerationService {

    private static final List<String> BANNED_KEYWORDS = Arrays.asList(
        "hate", "violence", "illegal", "harmful" // Simplified for demo
    );

    /**
     * Checks if a prompt or response violates safety policies.
     */
    public boolean isSafe(String content) {
        if (content == null) return true;
        
        String lowerContent = content.toLowerCase();
        for (String word : BANNED_KEYWORDS) {
            if (lowerContent.contains(word)) {
                log.warn("⚠️ [Moderation] Policy violation detected! Content contains banned word: {}", word);
                return false;
            }
        }
        return true;
    }

    /**
     * Determines if a response requires human review based on keywords or uncertainty.
     */
    public boolean requiresHumanReview(String content, double confidence) {
        return confidence < 0.7 || content.contains("not sure") || content.contains("consult an expert");
    }
}
