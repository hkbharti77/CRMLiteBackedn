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

    private final Cache<String, String> fuzzyCache = Caffeine.newBuilder()
            .maximumSize(5000)
            .expireAfterAccess(Duration.ofHours(6))
            .build();

    public Set<String> detectIntents(String text, NicheConfig config) {
        Set<String> intents = new HashSet<>();
        for (String word : text.split(" ")) {
            if (config.getIntents().contains(word)) intents.add(word);
            else {
                String matched = fuzzyMatch(word, config.getIntents());
                if (matched != null) intents.add(matched);
            }
        }
        return intents;
    }

    public List<String> extractEntities(String text, NicheConfig config) {
        List<String> detected = new ArrayList<>();
        String[] words = text.split(" ");
        
        for (String word : words) {
            String cleanWord = word.replaceAll("[^a-z0-9]", "");
            if (cleanWord.length() < 3) continue;

            String canonical = config.getEntities().get(cleanWord);
            if (canonical != null) {
                detected.add(canonical);
            } else {
                String matched = fuzzyMatch(cleanWord, config.getEntities().keySet());
                if (matched != null) detected.add(config.getEntities().get(matched));
            }
        }

        Set<String> uniqueEntities = new HashSet<>(detected);
        
        return uniqueEntities.stream()
                .sorted((e1, e2) -> {
                    int p1 = Integer.parseInt(config.getPriorities().getOrDefault(e1, "5"));
                    int p2 = Integer.parseInt(config.getPriorities().getOrDefault(e2, "5"));
                    if (p1 != p2) return p2 - p1;
                    return e1.compareTo(e2);
                })
                .limit(2)
                .collect(Collectors.toList());
    }

    public String applyHinglishMapping(String text, NicheConfig config) {
        String result = text;
        if (text.contains("kitna time")) return text.replace("kitna", "duration");
        
        for (Map.Entry<String, String> entry : config.getHinglish().entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
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
        if (text.length() < 3) {
            return false; 
        }
        Set<Character> uniqueChars = new HashSet<>();
        for (char c : text.toCharArray()) uniqueChars.add(c);
        double entropy = (double) uniqueChars.size() / text.length();
        
        return entropy < 0.20 || text.matches(".*(.)\\1{4,}.*");
    }

    private String fuzzyMatch(String word, Set<String> vocab) {
        if (word.length() < 3) return null;
        
        String cacheKey = word + ":" + vocab.hashCode();
        return fuzzyCache.get(cacheKey, k -> {
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
        });
    }

    private double calculateSimilarity(String s1, String s2) {
        int distance = levenshteinDistance(s1, s2);
        return 1.0 - ((double) distance / Math.max(s1.length(), s2.length()));
    }

    private int levenshteinDistance(String s1, String s2) {
        if (s1.length() < s2.length()) return levenshteinDistance(s2, s1);
        int[] dp = new int[s2.length() + 1];
        for (int i = 0; i <= s2.length(); i++) dp[i] = i;
        for (int i = 1; i <= s1.length(); i++) {
            int prev = i;
            for (int j = 1; j <= s2.length(); j++) {
                int next = (s1.charAt(i - 1) == s2.charAt(j - 1)) ? dp[j - 1] : 1 + Math.min(Math.min(dp[j], dp[j - 1]), prev);
                dp[j - 1] = prev;
                prev = next;
            }
            dp[s2.length()] = prev;
        }
        return dp[s2.length()];
    }
}
