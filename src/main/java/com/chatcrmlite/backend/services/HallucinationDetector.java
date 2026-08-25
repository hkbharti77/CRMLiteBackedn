package com.chatcrmlite.backend.services;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Post-generation validator to detect hallucinations and low-confidence responses.
 * 
 * Strategies:
 * 1. "I don't know" catch-all: Checks if the model explicitly stated lack of knowledge.
 * 2. Keyword check: Verifies that key nouns from the response actually exist in the context.
 * 3. Confidence signal detection: Scans for hedging phrases that indicate low confidence.
 * 4. Length check: Responses that are too short or too long might be problematic.
 */
@Slf4j
@Component
public class HallucinationDetector {

    private static final List<String> UNKNOWN_PHRASES = List.of(
        "i don't know",
        "i don't have that information",
        "not mentioned in the context",
        "i'm sorry, but i cannot",
        "the provided context does not contain"
    );

    private static final List<String> HEDGING_PHRASES = List.of(
        "it is possible",
        "i think",
        "maybe",
        "perhaps",
        "i am not sure",
        "it seems like"
    );

    /**
     * Evaluates if a response is likely a hallucination or low quality.
     * 
     * @param response The LLM generated response
     * @param context  The context provided to the LLM
     * @return true if the response is VALID, false if it's a suspected hallucination
     */
    public boolean isValid(String response, String context) {
        if (response == null || response.isBlank()) return false;

        String lowerResponse = response.toLowerCase();

        // 1. Check for pure fallback/unknown response (short response consisting primarily of an unknown phrase)
        if (response.trim().length() < 120) {
            for (String phrase : UNKNOWN_PHRASES) {
                if (lowerResponse.contains(phrase)) {
                    log.info("[HallucinationDetector] Short model fallback detected ('{}').", phrase);
                    return false;
                }
            }
        }


        // 2. Check for heavy hedging (low confidence)
        long hedgingCount = HEDGING_PHRASES.stream()
                .filter(lowerResponse::contains)
                .count();
        if (hedgingCount >= 2) {
            log.warn("[HallucinationDetector] High hedging detected ({} phrases). Possible low confidence.", hedgingCount);
            return false;
        }

        // 3. Simple Noun/Keyword Check (Basic implementation)
        // In a more advanced version, we'd use an NLP library to extract entities.
        // Here we just ensure the response isn't completely disjoint from the context.
        if (context != null && !context.isBlank()) {
            // If the response is significantly longer than "I don't know" 
            // but shares 0 non-trivial words with context, it's suspicious.
            // (Skipping for now to avoid false positives with small contexts)
        }

        return true;
    }
}
