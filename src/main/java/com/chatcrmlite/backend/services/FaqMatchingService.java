package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.models.FaqItem;
import com.chatcrmlite.backend.repositories.FaqItemRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class FaqMatchingService {

    @Autowired
    private FaqItemRepository faqItemRepository;

    @Value("${ai.faq.matching-threshold:0.85}")
    private float matchingThreshold;

    public static class MatchResult {
        private final FaqItem faqItem;
        private final float score;
        private final boolean isHighConfidence;

        public MatchResult(FaqItem faqItem, float score, boolean isHighConfidence) {
            this.faqItem = faqItem;
            this.score = score;
            this.isHighConfidence = isHighConfidence;
        }

        public FaqItem getFaqItem() { return faqItem; }
        public float getScore() { return score; }
        public boolean isHighConfidence() { return isHighConfidence; }
    }

    /**
     * High-Performance FAQ Matching Engine:
     * 1. Exact / Normalized String match (Score = 1.0)
     * 2. Cosine Vector Similarity against active FAQs (Score >= threshold, e.g. 0.85)
     */
    public MatchResult findBestMatch(UUID tenantId, String rawQuery, float[] queryEmbedding) {
        if (rawQuery == null || rawQuery.trim().isEmpty()) {
            return new MatchResult(null, 0.0f, false);
        }

        List<FaqItem> activeFaqs = faqItemRepository.findByTenantIdAndIsActiveTrue(tenantId);
        if (activeFaqs.isEmpty()) {
            return new MatchResult(null, 0.0f, false);
        }

        String normalizedQuery = normalizeText(rawQuery);

        FaqItem bestItem = null;
        float maxScore = 0.0f;

        for (FaqItem item : activeFaqs) {
            // 1. Exact question match check
            String normalizedFaqQ = normalizeText(item.getQuestion());
            if (normalizedQuery.equalsIgnoreCase(normalizedFaqQ)) {
                log.info("[FAQ-Engine] Exact question match hit! FAQ ID: {}, Question: '{}'", item.getId(), item.getQuestion());
                faqItemRepository.incrementHitCount(item.getId());
                return new MatchResult(item, 1.0f, true);
            }

            // 2. Cosine similarity via embeddings
            if (item.getEmbedding() != null && queryEmbedding != null) {
                float[] itemEmbedding = parseEmbedding(item.getEmbedding());
                if (itemEmbedding != null && itemEmbedding.length == queryEmbedding.length) {
                    float sim = cosineSimilarity(queryEmbedding, itemEmbedding);
                    if (sim > maxScore) {
                        maxScore = sim;
                        bestItem = item;
                    }
                }
            }
        }

        log.info("[FAQ-Engine] Top FAQ similarity score: {} (Threshold: {}) | Query: '{}'", 
                String.format("%.4f", maxScore), matchingThreshold, rawQuery);

        if (bestItem != null && maxScore >= matchingThreshold) {
            log.info("[FAQ-Engine] High-confidence match found! Score: {} >= {} | Question: '{}'", 
                    String.format("%.4f", maxScore), matchingThreshold, bestItem.getQuestion());
            faqItemRepository.incrementHitCount(bestItem.getId());
            return new MatchResult(bestItem, maxScore, true);
        }

        return new MatchResult(bestItem, maxScore, false);
    }

    private String normalizeText(String input) {
        if (input == null) return "";
        return input.trim()
                .toLowerCase()
                .replaceAll("[^a-zA-Z0-9\\s]", "")
                .replaceAll("\\s+", " ");
    }

    private float cosineSimilarity(float[] vectorA, float[] vectorB) {
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < vectorA.length; i++) {
            dotProduct += vectorA[i] * vectorB[i];
            normA += vectorA[i] * vectorA[i];
            normB += vectorB[i] * vectorB[i];
        }
        if (normA == 0.0 || normB == 0.0) return 0.0f;
        return (float) (dotProduct / (Math.sqrt(normA) * Math.sqrt(normB)));
    }

    public float[] parseEmbedding(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            String cleaned = raw.replace("[", "").replace("]", "").trim();
            if (cleaned.isEmpty()) return null;
            String[] parts = cleaned.split(",");
            float[] result = new float[parts.length];
            for (int i = 0; i < parts.length; i++) {
                result[i] = Float.parseFloat(parts[i].trim());
            }
            return result;
        } catch (Exception e) {
            log.error("[FAQ-Engine] Error parsing embedding string: {}", e.getMessage());
            return null;
        }
    }
}
