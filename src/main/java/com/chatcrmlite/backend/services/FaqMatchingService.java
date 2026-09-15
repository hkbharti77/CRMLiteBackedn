package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.models.FaqItem;
import com.chatcrmlite.backend.repositories.FaqItemRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
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
     * 1. Exact / Normalized String match via SQL
     * 2. Approximate nearest-neighbor search via HNSW pgvector
     */
    public MatchResult findBestMatch(UUID tenantId, String rawQuery, float[] queryEmbedding) {
        if (rawQuery == null || rawQuery.trim().isEmpty()) {
            return new MatchResult(null, 0.0f, false);
        }

        // 1. Exact match via Database
        Optional<FaqItem> exactMatch = faqItemRepository.findFirstByTenantAndExactQuestion(tenantId, rawQuery);
        if (exactMatch.isPresent()) {
            FaqItem item = exactMatch.get();
            log.info("[FAQ-Engine] Exact question match hit! FAQ ID: {}", item.getId());
            faqItemRepository.incrementHitCount(item.getId());
            return new MatchResult(item, 1.0f, true);
        }

        // 2. Approximate nearest-neighbor search using HNSW
        if (queryEmbedding == null) {
            return new MatchResult(null, 0.0f, false);
        }

        String embeddingLiteral = Arrays.toString(queryEmbedding);
        Optional<com.chatcrmlite.backend.repositories.FaqVectorMatch> nearest = faqItemRepository.findNearestByEmbedding(tenantId, embeddingLiteral);

        if (nearest.isPresent()) {
            com.chatcrmlite.backend.repositories.FaqVectorMatch match = nearest.get();
            double distance = match.getDistance() != null ? match.getDistance() : 1.0;
            float similarity = (float) (1.0 - distance);

            log.info("[FAQ-Engine] pgvector nearest distance: {} (similarity: {}, threshold: {}) | Query: '{}'",
                    String.format("%.4f", distance), String.format("%.4f", similarity),
                    matchingThreshold, rawQuery);

            if (similarity >= matchingThreshold) {
                // Fetch the fully managed entity safely
                Optional<FaqItem> itemOpt = faqItemRepository.findById(match.getId());
                if (itemOpt.isPresent()) {
                    log.info("[FAQ-Engine] High-confidence pgvector match! Score: {} >= {}", 
                            String.format("%.4f", similarity), matchingThreshold);
                    faqItemRepository.incrementHitCount(itemOpt.get().getId());
                    return new MatchResult(itemOpt.get(), similarity, true);
                }
            }
        }

        return new MatchResult(null, 0.0f, false);
    }

    private String normalizeText(String input) {
        if (input == null) return "";
        return input.trim().toLowerCase();
    }
}
