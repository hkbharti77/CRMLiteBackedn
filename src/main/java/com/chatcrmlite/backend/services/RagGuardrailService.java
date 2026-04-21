package com.chatcrmlite.backend.services;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.io.InputStream;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

@Service
@Slf4j
public class RagGuardrailService {

    private final ConcurrentHashMap<String, UserSession> userSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AtomicLong> aiHitsPerMinute = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastAlertTimeMap = new ConcurrentHashMap<>();
    
    @org.springframework.beans.factory.annotation.Autowired
    private AbuseDetectionService abuseDetectionService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, NicheConfig> nicheConfigs = new HashMap<>();
    private NicheConfig fallbackConfig;

    // Operational Controls
    private boolean shadowMode = false;

    public enum Decision {
        CALL_AI, CLARIFY, MENU, IGNORE, REUSE, WARNING
    }

    @Data
    public static class UserSession {
        private String lastMessage;
        private String lastDecisionReason;
        private Decision lastDecision;
        private String lastContextKey;
        private long lastUpdated;
        private AtomicInteger junkCount = new AtomicInteger(0);
        private AtomicInteger userFailures = new AtomicInteger(0);
    }

    @Builder
    @Data
    public static class GuardrailResult {
        private Decision decision;
        private String reason;
        private String detectedIntent;
        private String contextKey;
        private String suggestion;
    }

    @Data
    public static class NicheConfig {
        private String niche;
        private Set<String> intents;
        private Map<String, String> entities;
        private Map<String, String> hinglish;
        private Map<String, String> priorities;
    }

    @PostConstruct
    public void loadConfigs() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:guardrails/*.json");
            for (Resource resource : resources) {
                try (InputStream is = resource.getInputStream()) {
                    NicheConfig config = objectMapper.readValue(is, NicheConfig.class);
                    nicheConfigs.put(config.getNiche(), config);
                    log.info("Loaded Guardrail Config for Niche: {}", config.getNiche());
                    if (config.getNiche().equalsIgnoreCase("Other")) {
                        fallbackConfig = config;
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to load niche guardrail configs", e);
        }
    }

    public GuardrailResult evaluate(String rawText, String userId, boolean lastIsAi, String niche) {
        long startTime = System.currentTimeMillis();
        UserSession session = userSessions.computeIfAbsent(userId, k -> new UserSession());
        session.setLastUpdated(startTime);

        NicheConfig config = nicheConfigs.getOrDefault(niche, fallbackConfig);
        if (config == null) {
            // Absolute fallback if everything fails
            config = new NicheConfig();
            config.setIntents(new HashSet<>());
            config.setEntities(new HashMap<>());
            config.setHinglish(new HashMap<>());
            config.setPriorities(new HashMap<>());
        }

        try {
            // 1. Safe Length Cap & Truncation
            String text = rawText.trim();
            if (text.length() > 300) {
                int cutIndex = text.substring(0, 300).lastIndexOf(" ");
                text = (cutIndex > 0) ? text.substring(0, cutIndex) : text.substring(0, 300);
            }

            // 2. Normalization
            String normalizedText = text.toLowerCase().replaceAll("[^a-z0-9\\s?]", "");

            // 3. Abuse Detection Layer (Frozen Flow V8)
            AbuseDetectionService.AbuseResult abuse = abuseDetectionService.detectAndClean(text);
            if (abuse.isAbusive()) {
                // Determine if it's pure abuse or mixed with intent
                String scrubbedText = abuse.getCleanText();
                Set<String> scrubbedIntents = detectIntents(applyHinglishMapping(scrubbedText, config), config);
                
                if (scrubbedIntents.isEmpty()) {
                    // Case 1 & 3: Pure Abuse or Persistent Abuse
                    int strikes = session.getJunkCount().incrementAndGet();
                    if (strikes >= 3) {
                        return recordResult(GuardrailResult.builder()
                                .decision(Decision.IGNORE)
                                .reason("abuse_throttled")
                                .build());
                    }
                    return recordResult(GuardrailResult.builder()
                            .decision(Decision.WARNING)
                            .reason("pure_abuse_detected")
                            .suggestion("Please keep the conversation respectful. I'm here to help \uD83D\uDE42")
                            .build());
                } else {
                    // Case 2: Mixed Abuse - Scrub and continue
                    log.info("[Abuse] Mixed abuse scrubbed. Proceeding with clean text: '{}'", scrubbedText);
                    normalizedText = scrubbedText;
                }
            }

            // 4. Deduplication (Normalized + TTL 60s)
            if (session.getLastMessage() != null && 
                session.getLastMessage().equals(normalizedText) && 
                (startTime - session.getLastUpdated()) < 60000) {
                return recordResult(GuardrailResult.builder()
                        .decision(Decision.REUSE)
                        .reason("exact_duplicate_within_ttl")
                        .build());
            }

            // 4. Hinglish & Phrase Mapping
            String mappedText = applyHinglishMapping(normalizedText, config);

            // 5. Intent & Entity Extraction (Stable Key)
            Set<String> detectedIntents = detectIntents(mappedText, config);
            List<String> entities = extractEntities(mappedText, config);
            String primaryIntent = detectedIntents.isEmpty() ? "none" : detectedIntents.iterator().next();
            String contextKey = primaryIntent + ":" + (entities.isEmpty() ? "generic" : String.join("+", entities));

            // 6. Semantic Deduplication
            if (!contextKey.equals("none:generic") && 
                contextKey.equals(session.getLastContextKey()) && 
                (startTime - session.getLastUpdated()) < 60000) {
                return recordResult(GuardrailResult.builder()
                        .decision(Decision.REUSE)
                        .reason("semantic_duplicate_within_ttl")
                        .contextKey(contextKey)
                        .build());
            }

            // 7. Signal Scoring (Weighted)
            int score = calculateScore(mappedText, detectedIntents, entities);

            // 8. Context & First-Message Boost
            boolean isFollowUp = lastIsAi && (text.length() > 4 || text.contains("?"));
            if (isFollowUp) score += 30;
            if (session.getLastMessage() == null && score > 20) score += 20; // First impression boost

            // 9. Negation Check
            if (normalizedText.contains("no ") || normalizedText.contains("not ")) {
                score -= 30;
            }

            // 10. Junk Throttling
            if (isTrash(normalizedText)) {
                int junk = session.getJunkCount().incrementAndGet();
                if (junk >= 3) {
                    return recordResult(GuardrailResult.builder()
                            .decision(Decision.IGNORE)
                            .reason("spam_throttled")
                            .build());
                }
                return recordResult(GuardrailResult.builder().decision(Decision.MENU).reason("gibberish").build());
            }
            // session.getJunkCount().set(0); // REMOVED: Don't reset on mid-conversation junk if it's mixed with abuse

            // 11. Final Decision Router
            Decision decision;
            String reason;
            if (score >= 45 || (!detectedIntents.isEmpty() && score >= 30)) {
                decision = Decision.CALL_AI;
                reason = "high_signal_intent";
            } else if (score >= 15) {
                decision = Decision.CLARIFY;
                reason = "ambiguous_signal";
            } else {
                decision = Decision.MENU;
                reason = "low_signal_fallback";
            }

            GuardrailResult result = GuardrailResult.builder()
                    .decision(decision)
                    .reason(reason)
                    .detectedIntent(primaryIntent)
                    .contextKey(contextKey)
                    .suggestion(getSuggestion(primaryIntent))
                    .build();

            // Store state for next hit
            session.setLastMessage(normalizedText);
            session.setLastContextKey(contextKey);
            session.setLastDecision(decision);

            recordMetrics(decision, System.currentTimeMillis() - startTime);

            if (shadowMode) {
                log.info("[Shadow] Real Decision: {}, Score: {}, Text: '{}'", decision, score, text);
            }

            return result;

        } catch (Exception e) {
            log.error("Guardrail processing error", e);
            session.getUserFailures().incrementAndGet();
            throw e; // Rethrow to let WhatsAppService catch it for fail-open
        }
    }

    private String applyHinglishMapping(String text, NicheConfig config) {
        String result = text;
        // Specific phrases first
        if (text.contains("kitna time")) return text.replace("kitna", "duration");
        
        for (Map.Entry<String, String> entry : config.getHinglish().entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private Set<String> detectIntents(String text, NicheConfig config) {
        Set<String> intents = new HashSet<>();
        for (String word : text.split(" ")) {
            if (config.getIntents().contains(word)) intents.add(word);
            else {
                // Fuzzy match against controlled vocab
                String matched = fuzzyMatch(word, config.getIntents());
                if (matched != null) intents.add(matched);
            }
        }
        return intents;
    }

    private List<String> extractEntities(String text, NicheConfig config) {
        List<String> detected = new ArrayList<>();
        String[] words = text.split(" ");
        
        for (String word : words) {
            // Scrub punctuation
            String cleanWord = word.replaceAll("[^a-z0-9]", "");
            if (cleanWord.length() < 3) continue;

            String canonical = config.getEntities().get(cleanWord);
            if (canonical != null) {
                detected.add(canonical);
            } else {
                // Fuzzy match fallback for entities
                String matched = fuzzyMatch(cleanWord, config.getEntities().keySet());
                if (matched != null) detected.add(config.getEntities().get(matched));
            }
        }

        // De-duplicate
        Set<String> uniqueEntities = new HashSet<>(detected);
        
        // Priority-based Top-2 sorting
        return uniqueEntities.stream()
                .sorted((e1, e2) -> {
                    int p1 = Integer.parseInt(config.getPriorities().getOrDefault(e1, "5"));
                    int p2 = Integer.parseInt(config.getPriorities().getOrDefault(e2, "5"));
                    if (p1 != p2) return p2 - p1; // Higher priority first
                    return e1.compareTo(e2);     // Stable tie-break
                })
                .limit(2)
                .collect(Collectors.toList());
    }

    private int calculateScore(String text, Set<String> intents, List<String> entities) {
        int score = 0;
        score += intents.size() * 30;
        score += entities.size() * 20;
        if (text.contains("?")) score += 10;
        if (text.split(" ").length > 2) score += 10;
        return score;
    }

    private boolean isTrash(String text) {
        if (text.length() < 3) {
            // "ok", "hi", "no" are NOT trash, just low signal. 
            // Truly trash short inputs like "??" or ".." are handled by score = 0.
            return false; 
        }
        Set<Character> uniqueChars = new HashSet<>();
        for (char c : text.toCharArray()) uniqueChars.add(c);
        double entropy = (double) uniqueChars.size() / text.length();
        
        // Keyboard mashes like "asdfghjkl" or "aaaaaaa"
        return entropy < 0.35 || text.matches(".*(.)\\1{3,}.*"); // Repeats like "aaaaa"
    }

    private String fuzzyMatch(String word, Set<String> vocab) {
        if (word.length() < 3) return null;
        String bestMatch = null;
        double bestScore = 0;

        for (String candidate : vocab) {
            double similarity = calculateSimilarity(word, candidate);
            double threshold = candidate.length() <= 4 ? 0.9 : (candidate.length() <= 7 ? 0.8 : 0.75);
            if (similarity >= threshold && similarity > bestScore) {
                bestScore = similarity;
                bestMatch = candidate;
            }
        }
        return bestMatch;
    }

    private double calculateSimilarity(String s1, String s2) {
        int distance = levenshteinDistance(s1, s2);
        return 1.0 - ((double) distance / Math.max(s1.length(), s2.length()));
    }

    private int levenshteinDistance(String s1, String s2) {
        int[][] dp = new int[s1.length() + 1][s2.length() + 1];
        for (int i = 0; i <= s1.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= s2.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= s1.length(); i++) {
            for (int j = 1; j <= s2.length(); j++) {
                int cost = (s1.charAt(i - 1) == s2.charAt(j - 1)) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        return dp[s1.length()][s2.length()];
    }

    private String getSuggestion(String intent) {
        switch (intent) {
            case "price": return "Are you asking about consultation fees or treatment cost?";
            case "timing": return "Do you want our opening hours or specific doctor timings?";
            case "location": return "Are you looking for our address or a map link?";
            default: return "Could you please clarify what you're looking for?";
        }
    }

    private GuardrailResult recordResult(GuardrailResult result) {
        log.info("Guardrail Result: Decision={}, Reason={}", result.getDecision(), result.getReason());
        return result;
    }

    private void recordMetrics(Decision decision, long latency) {
        long minute = Instant.now().getEpochSecond() / 60;
        if (decision == Decision.CALL_AI) {
            aiHitsPerMinute.computeIfAbsent(minute, k -> new AtomicLong(0)).incrementAndGet();
            if (aiHitsPerMinute.get(minute).get() > 50) {
                triggerAlert("AI_SPIKE");
            }
        }
    }

    private void triggerAlert(String type) {
        long now = System.currentTimeMillis();
        lastAlertTimeMap.compute(type, (k, v) -> {
            if (v == null || (now - v) > 60000) {
                log.warn("PRODUCTION ALERT: {} detected!", type);
                return now;
            }
            return v;
        });
    }

    @Scheduled(fixedRate = 300000) // Every 5 minutes
    public void cleanup() {
        long now = System.currentTimeMillis();
        long currentMinute = Instant.now().getEpochSecond() / 60;

        userSessions.entrySet().removeIf(e -> (now - e.getValue().getLastUpdated()) > 600000); // 10 min
        aiHitsPerMinute.keySet().removeIf(m -> (currentMinute - m) > 60); // 60 min
        lastAlertTimeMap.entrySet().removeIf(e -> (now - e.getValue()) > 3600000); // 1 hour
        log.info("Guardrail Memory Cleanup completed. Active sessions: {}", userSessions.size());
    }

    public void resetMetrics(boolean force) {
        if (!force) return;
        aiHitsPerMinute.clear();
        log.info("Guardrail Metrics manually reset.");
    }
}