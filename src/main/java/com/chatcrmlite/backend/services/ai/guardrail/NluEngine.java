package com.chatcrmlite.backend.services.ai.guardrail;

import com.chatcrmlite.backend.dto.ai.NicheConfig;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class NluEngine {

    private final Cache<String, String> fuzzyCache;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;
    
    @org.springframework.beans.factory.annotation.Value("${app.performance.threshold.regex-ms:5}")
    private long regexThresholdMs;

    @org.springframework.beans.factory.annotation.Value("${app.performance.threshold.fuzzy-ms:20}")
    private long fuzzyThresholdMs;

    public NluEngine(io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.fuzzyCache = Caffeine.newBuilder()
                .maximumSize(5000)
                .expireAfterAccess(Duration.ofHours(6))
                .recordStats()
                .build();
        io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics.monitor(meterRegistry, fuzzyCache, "nlu_fuzzy_cache");
    }

    private static final java.util.regex.Pattern NON_ALPHANUMERIC_PATTERN = java.util.regex.Pattern.compile("[^a-z0-9]");
    private static final java.util.regex.Pattern REPEATED_CHAR_PATTERN = java.util.regex.Pattern.compile(".*(.)\\1{4,}.*");
    private static final java.util.regex.Pattern CONSONANT_CLUSTER_PATTERN = java.util.regex.Pattern.compile("[bcdfghjklmnpqrstvwxz]{5,}");
    private final Map<String, java.util.regex.Pattern> wordBoundaryPatternCache = new java.util.concurrent.ConcurrentHashMap<>();

    public Set<String> detectIntents(String text, NicheConfig config) {
        long start = System.currentTimeMillis();
        Set<String> intents = new HashSet<>();
        String contextId = (config.getNiche() != null ? config.getNiche() : "default") + ":intents";
        for (String word : text.split(" ")) {
            if (config.getIntents().contains(word)) intents.add(word);
            else {
                String matched = fuzzyMatch(word, config.getIntents(), contextId);
                if (matched != null) intents.add(matched);
            }
        }
        log.debug("NLU intent detection traceId={} intentCount={} processingTimeMs={}", 
                  org.slf4j.MDC.get("traceId"), intents.size(), System.currentTimeMillis() - start);
        return intents;
    }

    public List<String> extractEntities(String text, NicheConfig config) {
        long start = System.currentTimeMillis();
        List<String> detected = new ArrayList<>();
        String[] words = text.split(" ");
        java.util.regex.Matcher nonAlphaNumMatcher = NON_ALPHANUMERIC_PATTERN.matcher("");
        
        String contextId = (config.getNiche() != null ? config.getNiche() : "default") + ":entities";
        
        for (String word : words) {
            String cleanWord = nonAlphaNumMatcher.reset(word).replaceAll("");
            if (cleanWord.length() < 3) continue;

            String canonical = config.getEntities().get(cleanWord);
            if (canonical != null) {
                detected.add(canonical);
            } else {
                String matched = fuzzyMatch(cleanWord, config.getEntities().keySet(), contextId);
                if (matched != null) detected.add(config.getEntities().get(matched));
            }
        }

        Set<String> uniqueEntities = new HashSet<>(detected);
        
        List<String> results = uniqueEntities.stream()
                .sorted((e1, e2) -> {
                    int p1 = Integer.parseInt(config.getPriorities().getOrDefault(e1, "5"));
                    int p2 = Integer.parseInt(config.getPriorities().getOrDefault(e2, "5"));
                    if (p1 != p2) return p2 - p1;
                    return e1.compareTo(e2);
                })
                .limit(2)
                .collect(Collectors.toList());

        log.debug("NLU entity extraction traceId={} entityCount={} processingTimeMs={}", 
                  org.slf4j.MDC.get("traceId"), results.size(), System.currentTimeMillis() - start);
        return results;
    }

    private final Map<String, java.util.regex.Pattern> combinedHinglishPatternCache = new java.util.concurrent.ConcurrentHashMap<>();

    public String applyHinglishMapping(String text, NicheConfig config) {
        io.micrometer.core.instrument.Timer.Sample sample = io.micrometer.core.instrument.Timer.start(meterRegistry);
        long start = System.currentTimeMillis();
        try {
            if (text.contains("kitna time")) {
                text = wordBoundaryPatternCache.computeIfAbsent("kitna", k -> java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(k) + "\\b"))
                        .matcher(text).replaceAll("duration");
            }
            
            if (config.getHinglish() == null || config.getHinglish().isEmpty()) {
                return text;
            }

            String cacheKey = generateHinglishCacheKey(config);
            
            java.util.regex.Pattern pattern = combinedHinglishPatternCache.computeIfAbsent(cacheKey, k -> {
                // Sort keys by length descending to ensure longer phrases match before substrings (Trie-like behavior)
                String combined = config.getHinglish().keySet().stream()
                        .sorted(Comparator.comparingInt(String::length).reversed())
                        .map(java.util.regex.Pattern::quote)
                        .collect(Collectors.joining("|", "\\b(", ")\\b"));
                return java.util.regex.Pattern.compile(combined);
            });

            java.util.regex.Matcher matcher = pattern.matcher(text);
            if (!matcher.find()) {
                return text;
            }
            
            StringBuilder sb = new StringBuilder(text.length());
            do {
                String matchedWord = matcher.group(1);
                String replacement = config.getHinglish().get(matchedWord);
                if (replacement != null) {
                    matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
                } else {
                    matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(matchedWord));
                }
            } while (matcher.find());
            matcher.appendTail(sb);
            
            return sb.toString();
        } finally {
            long duration = System.currentTimeMillis() - start;
            sample.stop(meterRegistry.timer("nlu.regex.duration", "method", "applyHinglishMapping"));
            if (duration > regexThresholdMs) {
                log.warn("SLOW_OPERATION: Regex applyHinglishMapping exceeded threshold traceId={} durationMs={} thresholdMs={}", 
                         org.slf4j.MDC.get("traceId"), duration, regexThresholdMs);
            }
        }
    }

    public int calculateScore(String text, Set<String> intents, List<String> entities) {
        int score = 0;
        score += intents.size() * 30;
        score += entities.size() * 20;
        if (text.contains("?")) score += 10;
        if (text.split(" ").length > 2) score += 10;
        return score;
    }

    public boolean isTrash(String text) {
        io.micrometer.core.instrument.Timer.Sample sample = io.micrometer.core.instrument.Timer.start(meterRegistry);
        long start = System.currentTimeMillis();
        try {
            if (text.length() < 3) {
                return false; 
            }
            // Check for keyboard mashing consonant clusters (e.g. jjhdgkjdfkga, asdfghjkl)
            if (CONSONANT_CLUSTER_PATTERN.matcher(text).find()) {
                return true;
            }
            Set<Character> uniqueChars = new HashSet<>();
            for (char c : text.toCharArray()) uniqueChars.add(c);
            double entropy = (double) uniqueChars.size() / text.length();
            
            return entropy < 0.20 || REPEATED_CHAR_PATTERN.matcher(text).matches();
        } finally {
            long duration = System.currentTimeMillis() - start;
            sample.stop(meterRegistry.timer("nlu.regex.duration", "method", "isTrash"));
            if (duration > regexThresholdMs) {
                log.warn("SLOW_OPERATION: Regex isTrash exceeded threshold traceId={} durationMs={} thresholdMs={}", 
                         org.slf4j.MDC.get("traceId"), duration, regexThresholdMs);
            }
        }
    }

    private String fuzzyMatch(String word, Set<String> vocab, String contextId) {
        if (word.length() < 3) return null;
        
        String cacheKey = contextId + ":" + word;
        return fuzzyCache.get(cacheKey, k -> {
            io.micrometer.core.instrument.Timer.Sample sample = io.micrometer.core.instrument.Timer.start(meterRegistry);
            long start = System.currentTimeMillis();
            try {
                String bestMatch = null;
                double bestScore = 0;

                for (String candidate : vocab) {
                    if (Math.abs(word.length() - candidate.length()) > 2) continue;

                    double similarity = calculateSimilarity(word, candidate);
                    double threshold = candidate.length() <= 4 ? 0.9 : (candidate.length() <= 7 ? 0.8 : 0.75);
                    if (similarity >= threshold && similarity > bestScore) {
                        bestScore = similarity;
                        bestMatch = candidate;
                    }
                }
                return bestMatch;
            } finally {
                long duration = System.currentTimeMillis() - start;
                sample.stop(meterRegistry.timer("nlu.fuzzy.duration", "method", "fuzzyMatch"));
                if (duration > fuzzyThresholdMs) {
                    log.warn("SLOW_OPERATION: Fuzzy search exceeded threshold traceId={} wordLength={} vocabSize={} durationMs={} thresholdMs={}", 
                             org.slf4j.MDC.get("traceId"), word.length(), vocab.size(), duration, fuzzyThresholdMs);
                }
            }
        });
    }

    private double calculateSimilarity(String s1, String s2) {
        int distance = levenshteinDistance(s1, s2);
        return 1.0 - ((double) distance / Math.max(s1.length(), s2.length()));
    }

    private static final ThreadLocal<int[]> LEVENSHTEIN_BUFFER = ThreadLocal.withInitial(() -> new int[64]);

    private int levenshteinDistance(String s1, String s2) {
        if (s1.length() < s2.length()) return levenshteinDistance(s2, s1);
        
        int len1 = s1.length();
        int len2 = s2.length();
        
        int[] dp = LEVENSHTEIN_BUFFER.get();
        if (dp.length < len2 + 1) {
            dp = new int[Math.max(dp.length * 2, len2 + 1)];
            LEVENSHTEIN_BUFFER.set(dp);
        }
        
        for (int i = 0; i <= len2; i++) dp[i] = i;
        for (int i = 1; i <= len1; i++) {
            int prev = i;
            for (int j = 1; j <= len2; j++) {
                int next = (s1.charAt(i - 1) == s2.charAt(j - 1)) ? dp[j - 1] : 1 + Math.min(Math.min(dp[j], dp[j - 1]), prev);
                dp[j - 1] = prev;
                prev = next;
            }
            dp[len2] = prev;
        }
        return dp[len2];
    }

    /**
     * Generates a deterministic and immutable cache key for Hinglish mappings.
     * 
     * Strategy preference order:
     * 1. tenantId (Not currently available in NicheConfig)
     * 2. nicheId (Used as a shared configuration identifier if present)
     * 3. configurationId (Not currently available in NicheConfig)
     * 4. A deterministic canonical representation of the Hinglish configuration (fallback)
     * 
     * This completely replaces the previous usage of mutable hashCode(), 
     * eliminating cross-tenant collisions and ensuring cache isolation.
     */
    private String generateHinglishCacheKey(NicheConfig config) {
        if (config.getNiche() != null && !config.getNiche().isEmpty()) {
            return "niche:" + config.getNiche();
        }
        
        if (config.getHinglish() == null || config.getHinglish().isEmpty()) {
            return "empty";
        }

        // Fallback: Deterministic canonical representation
        return config.getHinglish().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("|", "custom:", ""));
    }
}
