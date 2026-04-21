package com.chatcrmlite.backend.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.util.*;

/**
 * Enterprise-grade 3-Rule Flow Trigger Engine.
 *
 * Rule 1 — Direct Word/Phrase Match:
 *   If the incoming message exactly contains any phrase from "direct_triggers", trigger immediately.
 *
 * Rule 2 — Intent Sentence Match:
 *   If the message contains any phrase from any *_intent group inside "intent_triggers", trigger immediately.
 *
 * Rule 3 — Scoring (NLP-lite):
 *   If the message has > min_words_for_scoring words,
 *   score each word against "entity_keywords" (with entity_boost per match).
 *   If total score >= similarity_threshold, trigger.
 *
 * Source: Logic pattern adapted from lead_keywords.json reference (Mobiloitte AI Agent).
 */
@Slf4j
public class FlowTriggerEngine {

    /** Loaded config from JSON  */
    private final List<String> directTriggers;
    private final Map<String, List<String>> intentTriggers;
    private final Map<String, List<String>> entityKeywords;
    private final int similarityThreshold;
    private final int minWordsForScoring;
    private final int entityBoost;

    // ── Constructor: loads from resources/triggers/<slug>.json ──────────────
    public FlowTriggerEngine(String subCategoryName, ObjectMapper objectMapper) {
        String slug = toSlug(subCategoryName);
        String resourcePath = "/triggers/" + slug + ".json";

        Map<String, Object> raw = null;
        try (InputStream is = FlowTriggerEngine.class.getResourceAsStream(resourcePath)) {
            if (is != null) {
                raw = objectMapper.readValue(is, new TypeReference<>() {});
                log.debug("[TriggerEngine] Loaded config for slug={}", slug);
            } else {
                log.warn("[TriggerEngine] No trigger file found for slug='{}', using defaults", slug);
            }
        } catch (Exception e) {
            log.error("[TriggerEngine] Failed to load trigger config for {}: {}", slug, e.getMessage());
        }

        if (raw != null) {
            this.directTriggers = castList(raw.get("direct_triggers"));
            this.intentTriggers = castMapOfLists(raw.get("intent_triggers"));
            this.entityKeywords = castMapOfLists(raw.get("entity_keywords"));

            @SuppressWarnings("unchecked")
            Map<String, Object> config = (Map<String, Object>) raw.getOrDefault("config", Map.of());
            this.similarityThreshold = ((Number) config.getOrDefault("similarity_threshold", 80)).intValue();
            this.minWordsForScoring  = ((Number) config.getOrDefault("min_words_for_scoring", 4)).intValue();
            this.entityBoost         = ((Number) config.getOrDefault("entity_boost", 15)).intValue();
        } else {
            // Fallback defaults (for unknown sub-categories)
            this.directTriggers = List.of("book","appointment","inquiry","consult","help");
            this.intentTriggers = Map.of("generic", List.of("i need help","tell me more","book a slot"));
            this.entityKeywords = Map.of("generic", List.of("book","service","consult","inquiry","need"));
            this.similarityThreshold = 80;
            this.minWordsForScoring  = 4;
            this.entityBoost         = 15;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Public: Evaluate message against all 3 rules
    // ════════════════════════════════════════════════════════════════════════

    public TriggerResult evaluate(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return TriggerResult.notTriggered();
        }

        String message = rawMessage.trim().toLowerCase();

        // ── Rule 1: Direct Trigger (exact substring match) ───────────────────
        for (String phrase : directTriggers) {
            if (message.contains(phrase.toLowerCase())) {
                log.debug("[TriggerEngine] Rule1 DIRECT HIT: phrase='{}' in msg='{}'", phrase, message);
                return TriggerResult.triggered(TriggerResult.Rule.DIRECT_PHRASE, 100, phrase);
            }
        }

        // ── Rule 2: Intent Trigger ───────────────────────────────────────────
        for (Map.Entry<String, List<String>> intentGroup : intentTriggers.entrySet()) {
            for (String intentPhrase : intentGroup.getValue()) {
                if (message.contains(intentPhrase.toLowerCase())) {
                    log.debug("[TriggerEngine] Rule2 INTENT HIT: intent='{}' phrase='{}'",
                              intentGroup.getKey(), intentPhrase);
                    return TriggerResult.triggered(TriggerResult.Rule.INTENT_PHRASE, 90, intentGroup.getKey());
                }
            }
        }

        // ── Rule 3: Scoring (only if message has enough words) ───────────────
        String[] words = message.split("\\s+");
        if (words.length >= minWordsForScoring) {
            int score = computeScore(words);
            log.debug("[TriggerEngine] Rule3 SCORE: {} (threshold={})", score, similarityThreshold);
            if (score >= similarityThreshold) {
                return TriggerResult.triggered(TriggerResult.Rule.SCORE_MATCH, score, "score=" + score);
            }
        }

        return TriggerResult.notTriggered();
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Internal: Entity scoring
    // ════════════════════════════════════════════════════════════════════════

    private int computeScore(String[] messageWords) {
        int total = 0;
        // Flatten all entity keywords from all groups
        Set<String> seen = new HashSet<>();

        for (Map.Entry<String, List<String>> group : entityKeywords.entrySet()) {
            for (String keyword : group.getValue()) {
                String kw = keyword.toLowerCase();
                if (seen.contains(kw)) continue;

                // Check if any word in the message contains this keyword
                for (String word : messageWords) {
                    if (word.contains(kw) || kw.contains(word)) {
                        total += entityBoost;
                        seen.add(kw);
                        break;
                    }
                }
            }
        }
        return total;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Static utility: convert sub-category name to JSON file slug
    //  e.g. "Dental Clinics" → "dental-clinics"
    //       "Freelance Web/Graphic Designers" → "freelance-web-graphic-designers"
    // ════════════════════════════════════════════════════════════════════════

    public static String toSlug(String name) {
        if (name == null) return "generic";
        return name.trim()
                .toLowerCase()
                .replaceAll("[/&]", " ")           // replace / and & with space
                .replaceAll("[^a-z0-9\\s-]", "")   // remove special chars
                .replaceAll("\\s+", "-")            // spaces → hyphens
                .replaceAll("-+", "-")              // collapse multiple hyphens
                .replaceAll("^-|-$", "");           // trim leading/trailing hyphens
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Result DTO
    // ════════════════════════════════════════════════════════════════════════

    public record TriggerResult(boolean triggered, Rule rule, int score, String matchedOn) {

        public enum Rule { DIRECT_PHRASE, INTENT_PHRASE, SCORE_MATCH, NONE }

        public static TriggerResult triggered(Rule rule, int score, String matchedOn) {
            return new TriggerResult(true, rule, score, matchedOn);
        }

        public static TriggerResult notTriggered() {
            return new TriggerResult(false, Rule.NONE, 0, null);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Safe casts from raw JSON map
    // ════════════════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private List<String> castList(Object obj) {
        if (obj instanceof List<?> list) {
            return (List<String>) list;
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, List<String>> castMapOfLists(Object obj) {
        if (obj instanceof Map<?, ?> map) {
            return (Map<String, List<String>>) map;
        }
        return Map.of();
    }
}
