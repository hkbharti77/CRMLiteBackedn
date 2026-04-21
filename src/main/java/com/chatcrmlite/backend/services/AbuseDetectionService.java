package com.chatcrmlite.backend.services;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@Slf4j
public class AbuseDetectionService {

    // English Profanity
    private static final Set<String> ABUSE_EN = Set.of(
            "fuck", "shit", "bitch", "asshole", "bastard", "dick", "pussy"
    );

    // Hindi / Hinglish Profanity
    private static final Set<String> ABUSE_HI = Set.of(
            "chutiya", "madarchod", "maderchod", "bhenchod", "behenchod", 
            "mc", "bc", "gandu", "harami", "saala", "kamine", "lodu"
    );

    // Regex for masked/repetitive abuse
    private static final Pattern MASKED_ABUSE_PATTERN = Pattern.compile(
            ".*(m+c|b+c|c+h+u+t+i+y+a+|f+u+c+k|s+h+i+t).*|.*(.)\\1{4,}.*", 
            Pattern.CASE_INSENSITIVE
    );

    @Data
    @Builder
    public static class AbuseResult {
        private boolean isAbusive;
        private int abuseScore;
        private String cleanText;
    }

    public AbuseResult detectAndClean(String text) {
        if (text == null || text.isBlank()) {
            return AbuseResult.builder().isAbusive(false).abuseScore(0).cleanText(text).build();
        }

        String normalizedText = text.toLowerCase();
        
        // 1. Aggressive Normalization: Collapse spaced abuse (m c -> mc)
        String collapsed = normalizedText.replaceAll("\\s+", " ");
        collapsed = collapsed.replace("c h u t i y a", "chutiya")
                             .replace("m a d a r c h o d", "madarchod")
                             .replace("b h e n c h o d", "bhenchod")
                             .replace("m c", "mc")
                             .replace("b c", "bc");

        int abuseScore = 0;
        Set<String> wordsToScrub = new HashSet<>();
        String[] words = collapsed.split(" ");

        // 2. Token-level detection
        for (String word : words) {
            String cleanWord = word.replaceAll("[^a-z]", "");
            if (ABUSE_EN.contains(cleanWord) || ABUSE_HI.contains(cleanWord)) {
                abuseScore++;
                wordsToScrub.add(word);
            }
        }

        // 3. Pattern-level detection (Regex)
        if (MASKED_ABUSE_PATTERN.matcher(collapsed).matches()) {
            abuseScore += 2; // Regex matches are higher confidence abuse
            // Scrub words that match the pattern
            for (String word : words) {
                if (MASKED_ABUSE_PATTERN.matcher(word).matches()) {
                    wordsToScrub.add(word);
                }
            }
        }

        // 4. Scrubbing
        String cleanText = collapsed;
        for (String scrubWord : wordsToScrub) {
            cleanText = cleanText.replace(scrubWord, "").replaceAll("\\s+", " ").trim();
        }

        return AbuseResult.builder()
                .isAbusive(abuseScore > 0)
                .abuseScore(abuseScore)
                .cleanText(cleanText)
                .build();
    }
}
