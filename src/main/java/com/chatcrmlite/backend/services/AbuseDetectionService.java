package com.chatcrmlite.backend.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

@Service
public class AbuseDetectionService {
    private static final Logger log = LoggerFactory.getLogger(AbuseDetectionService.class);

    // English Profanity
    private static final Set<String> ABUSE_EN = Set.of(
            "fuck", "shit", "bitch", "asshole", "bastard", "dick", "pussy"
    );

    // Hindi / Hinglish Profanity
    private static final Set<String> ABUSE_HI = Set.of(
            "chutiya", "madarchod", "maderchod", "bhenchod", "behenchod", 
            "mc", "bc", "gandu", "harami", "saala", "kamine", "lodu"
    );

    // Regex for masked/repetitive abuse - Optimized to prevent ReDoS
    private static final Pattern MASKED_ABUSE_PATTERN = Pattern.compile(
            "m+c+|b+c+|c+h+u+t+i+y+a+|f+u+c+k+|s+h+i+t+", 
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern REPETITIVE_CHAR_PATTERN = Pattern.compile("(.)\\1{5,}");

    public static class AbuseResult {
        private boolean isAbusive;
        private int abuseScore;
        private String cleanText;

        public AbuseResult() {}

        public AbuseResult(boolean isAbusive, int abuseScore, String cleanText) {
            this.isAbusive = isAbusive;
            this.abuseScore = abuseScore;
            this.cleanText = cleanText;
        }

        public boolean isAbusive() { return isAbusive; }
        public void setAbusive(boolean abusive) { isAbusive = abusive; }
        public int getAbuseScore() { return abuseScore; }
        public void setAbuseScore(int abuseScore) { this.abuseScore = abuseScore; }
        public String getCleanText() { return cleanText; }
        public void setCleanText(String cleanText) { this.cleanText = cleanText; }

        public static AbuseResultBuilder builder() { return new AbuseResultBuilder(); }

        public static class AbuseResultBuilder {
            private boolean isAbusive;
            private int abuseScore;
            private String cleanText;

            public AbuseResultBuilder isAbusive(boolean isAbusive) { this.isAbusive = isAbusive; return this; }
            public AbuseResultBuilder abuseScore(int abuseScore) { this.abuseScore = abuseScore; return this; }
            public AbuseResultBuilder cleanText(String cleanText) { this.cleanText = cleanText; return this; }

            public AbuseResult build() {
                return new AbuseResult(isAbusive, abuseScore, cleanText);
            }
        }
    }

    public AbuseResult detectAndClean(String text) {
        if (text == null || text.isBlank()) {
            return AbuseResult.builder().isAbusive(false).abuseScore(0).cleanText(text).build();
        }

        String normalizedText = text.toLowerCase();
        
        // Fast path for clean text
        if (!normalizedText.contains(" ") && normalizedText.length() < 3) {
             return AbuseResult.builder().isAbusive(false).abuseScore(0).cleanText(text).build();
        }

        String collapsed = normalizedText.replaceAll("\\s+", " ");
        collapsed = collapsed.replace("c h u t i y a", "chutiya")
                             .replace("m a d a r c h o d", "madarchod")
                             .replace("b h e n c h o d", "bhenchod")
                             .replace("m c", "mc")
                             .replace("b c", "bc");

        int abuseScore = 0;
        Set<String> wordsToScrub = new LinkedHashSet<>();
        String[] words = collapsed.split(" ");

        for (String word : words) {
            String cleanWord = word.replaceAll("[^a-z]", "");
            if (ABUSE_EN.contains(cleanWord) || ABUSE_HI.contains(cleanWord)) {
                abuseScore++;
                wordsToScrub.add(word);
            }
        }

        if (MASKED_ABUSE_PATTERN.matcher(collapsed).find() || REPETITIVE_CHAR_PATTERN.matcher(collapsed).find()) {
            abuseScore += 2; 
            for (String word : words) {
                if (MASKED_ABUSE_PATTERN.matcher(word).matches() || REPETITIVE_CHAR_PATTERN.matcher(word).find()) {
                    wordsToScrub.add(word);
                }
            }
        }

        if (wordsToScrub.isEmpty()) {
            return AbuseResult.builder().isAbusive(abuseScore > 0).abuseScore(abuseScore).cleanText(collapsed).build();
        }

        // Efficient scrubbing using descending length order
        List<String> sortedScrub = new ArrayList<>(wordsToScrub);
        sortedScrub.sort((a, b) -> b.length() - a.length());

        String resultText = collapsed;
        for (String scrubWord : sortedScrub) {
            resultText = resultText.replace(scrubWord, "***");
        }

        return AbuseResult.builder()
                .isAbusive(abuseScore > 0)
                .abuseScore(abuseScore)
                .cleanText(resultText.replaceAll("\\s+", " ").trim())
                .build();
    }
}
