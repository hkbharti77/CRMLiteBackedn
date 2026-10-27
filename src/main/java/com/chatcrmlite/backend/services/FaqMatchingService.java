package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.models.FaqItem;
import com.chatcrmlite.backend.repositories.FaqItemRepository;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Slf4j
@Service
public class FaqMatchingService {

    @Autowired
    private FaqItemRepository faqItemRepository;

    @Autowired(required = false)
    @Qualifier("faqAnalyticsExecutor")
    private Executor faqAnalyticsExecutor;

    @Value("${ai.faq.matching-threshold:0.75}")
    private float matchingThreshold;

    /**
     * Immutable tenant snapshot for high-throughput concurrent in-memory FAQ reads.
     */
    public record TenantFaqSnapshot(
            UUID tenantId,
            Map<String, FaqItem> normalizedQuestionMap,
            List<FaqItem> activeFaqs,
            long loadedTimestamp
    ) {}

    /**
     * Caffeine in-memory tenant cache:
     * - Single-flight loading via LoadingCache (protects DB from request stampedes).
     * - Strict tenant boundary (Tenant A never sees Tenant B).
     * - 15-minute write-expiry with maximum 1000 active tenants in memory.
     */
    private final LoadingCache<UUID, TenantFaqSnapshot> tenantFaqCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(15, TimeUnit.MINUTES)
            .build(this::loadTenantFaqSnapshot);

    public static class MatchResult {
        private final FaqItem faqItem;
        private final float score;
        private final boolean isHighConfidence;
        private final String matchType; // EXACT, SEMANTIC, NONE

        public MatchResult(FaqItem faqItem, float score, boolean isHighConfidence) {
            this(faqItem, score, isHighConfidence, isHighConfidence ? "EXACT" : "NONE");
        }

        public MatchResult(FaqItem faqItem, float score, boolean isHighConfidence, String matchType) {
            this.faqItem = faqItem;
            this.score = score;
            this.isHighConfidence = isHighConfidence;
            this.matchType = matchType;
        }

        public FaqItem getFaqItem() { return faqItem; }
        public float getScore() { return score; }
        public boolean isHighConfidence() { return isHighConfidence; }
        public String getMatchType() { return matchType; }
    }

    /**
     * Regex guard to detect compound / multi-intent queries.
     * Prevents false short-circuiting on queries like:
     * "Does Gyan VaniAi work with international clients, and what are the pricing plans?"
     */
    private static final Pattern COMPOUND_QUERY_PATTERN = Pattern.compile(
            "\\b(and also|and what|and how|and where|and when|and who|and why|along with|as well as|plus tell me)\\b|"
                    + "[,;]\\s*(and\\s+)?(what|how|where|when|who|why|is there|do you|can you|pricing|price|cost|timing|contact)"
    );

    /**
     * LEVEL 1 (Ultra-Fast Path): In-Memory Exact & Normalized FAQ Match (< 1 ms).
     * Zero ONNX CPU inferences, zero database round-trips for warm cache hits.
     */
    public MatchResult findExactNormalizedMatch(UUID tenantId, String rawQuery) {
        if (tenantId == null || rawQuery == null || rawQuery.isBlank()) {
            return new MatchResult(null, 0.0f, false, "NONE");
        }

        String normalized = normalizeQuestion(rawQuery);
        if (normalized.isEmpty()) {
            return new MatchResult(null, 0.0f, false, "NONE");
        }

        // Guard: Multi-intent / compound queries must fall through to full semantic/RAG processing
        if (isCompoundQuery(normalized)) {
            log.info("[FAQ-FastPath] Compound / multi-intent query detected ('{}') - bypassing single FAQ match.", rawQuery);
            return new MatchResult(null, 0.0f, false, "COMPOUND_BYPASS");
        }

        TenantFaqSnapshot snapshot = tenantFaqCache.get(tenantId);
        if (snapshot == null || snapshot.normalizedQuestionMap().isEmpty()) {
            return new MatchResult(null, 0.0f, false, "NONE");
        }

        // 1. Direct normalized text match
        FaqItem match = snapshot.normalizedQuestionMap().get(normalized);

        // 2. Fallback check: stripped of trailing question mark if present
        if (match == null && normalized.endsWith("?")) {
            match = snapshot.normalizedQuestionMap().get(normalized.substring(0, normalized.length() - 1).trim());
        }

        if (match != null) {
            log.info("[FAQ-FastPath] IN-MEMORY EXACT HIT for tenant: {} | FAQ ID: {} | Q: '{}'",
                    tenantId, match.getId(), match.getQuestion());
            recordHitCountAsync(match.getId());
            return new MatchResult(match, 1.0f, true, "EXACT");
        }

        return new MatchResult(null, 0.0f, false, "NONE");
    }

    /**
     * LEVEL 2 (Semantic Path): HNSW pgvector nearest-neighbor search.
     * Evaluated ONLY when exact normalized match misses.
     */
    public MatchResult findSemanticMatch(UUID tenantId, String rawQuery, float[] queryEmbedding) {
        if (tenantId == null || rawQuery == null || rawQuery.isBlank() || queryEmbedding == null) {
            return new MatchResult(null, 0.0f, false, "NONE");
        }

        String embeddingLiteral = Arrays.toString(queryEmbedding);
        Optional<com.chatcrmlite.backend.repositories.FaqVectorMatch> nearest =
                faqItemRepository.findNearestByEmbedding(tenantId, embeddingLiteral);

        if (nearest.isPresent()) {
            com.chatcrmlite.backend.repositories.FaqVectorMatch match = nearest.get();
            double distance = match.getDistance() != null ? match.getDistance() : 1.0;
            float similarity = (float) (1.0 - distance);

            log.info("[FAQ-Semantic] pgvector nearest distance: {} (similarity: {}, threshold: {}) | Query: '{}'",
                    String.format("%.4f", distance), String.format("%.4f", similarity),
                    matchingThreshold, rawQuery);

            if (similarity >= matchingThreshold) {
                // Fetch the fully managed entity
                Optional<FaqItem> itemOpt = faqItemRepository.findById(match.getId());
                if (itemOpt.isPresent()) {
                    FaqItem item = itemOpt.get();
                    log.info("[FAQ-Semantic] High-confidence pgvector match! Score: {} >= {} | Question: '{}'",
                            String.format("%.4f", similarity), matchingThreshold, item.getQuestion());
                    recordHitCountAsync(item.getId());
                    return new MatchResult(item, similarity, true, "SEMANTIC");
                }
            }
        }

        return new MatchResult(null, 0.0f, false, "NONE");
    }

    /**
     * Backward-compatible dispatcher method.
     */
    public MatchResult findBestMatch(UUID tenantId, String rawQuery, float[] queryEmbedding) {
        MatchResult exact = findExactNormalizedMatch(tenantId, rawQuery);
        if (exact.isHighConfidence()) {
            return exact;
        }
        return findSemanticMatch(tenantId, rawQuery, queryEmbedding);
    }

    /**
     * Single-flight database loader for tenant FAQs.
     */
    private TenantFaqSnapshot loadTenantFaqSnapshot(UUID tenantId) {
        long start = System.currentTimeMillis();
        List<FaqItem> activeItems = faqItemRepository.findByTenantIdAndIsActiveTrue(tenantId);
        Map<String, FaqItem> map = new HashMap<>();

        for (FaqItem item : activeItems) {
            if (item.getQuestion() != null && !item.getQuestion().isBlank()) {
                String norm = normalizeQuestion(item.getQuestion());
                if (!norm.isEmpty()) {
                    map.put(norm, item);
                }
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("[FAQ-Cache] Loaded snapshot for tenant {} ({} active FAQs) in {}ms",
                tenantId, activeItems.size(), elapsed);

        return new TenantFaqSnapshot(
                tenantId,
                Collections.unmodifiableMap(map),
                List.copyOf(activeItems),
                System.currentTimeMillis()
        );
    }

    /**
     * Consistent question normalizer.
     * Lowercases, collapses whitespace, and trims leading/trailing punctuation.
     */
    public String normalizeQuestion(String input) {
        if (input == null) return "";
        String text = input.toLowerCase(Locale.ROOT).trim();
        // Remove leading/trailing punctuation and symbols
        text = text.replaceAll("^[\\p{Punct}\\s]+|[\\p{Punct}\\s]+$", "");
        // Collapse internal multiple whitespaces
        text = text.replaceAll("\\s+", " ").trim();
        return text;
    }

    /**
     * Checks if a query contains conjunctions linking multiple distinct questions.
     */
    public boolean isCompoundQuery(String normalizedQuery) {
        if (normalizedQuery == null || normalizedQuery.isBlank()) return false;
        return COMPOUND_QUERY_PATTERN.matcher(normalizedQuery).find();
    }

    /**
     * Transaction-aware cache invalidation:
     * Defers eviction until AFTER database transaction commit to prevent reading stale state.
     */
    public void invalidateTenantCache(UUID tenantId) {
        if (tenantId == null) return;

        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    tenantFaqCache.invalidate(tenantId);
                    log.info("[FAQ-Cache] Invalidated cache for tenant {} after transaction commit.", tenantId);
                }
            });
        } else {
            tenantFaqCache.invalidate(tenantId);
            log.info("[FAQ-Cache] Invalidated cache for tenant {} immediately (no active transaction).", tenantId);
        }
    }

    /**
     * Reliable asynchronous hit-count update using dedicated bounded executor.
     * Prevents database write latency from blocking client response.
     */
    public void recordHitCountAsync(UUID faqId) {
        if (faqId == null) return;

        Runnable task = () -> {
            try {
                faqItemRepository.incrementHitCount(faqId);
            } catch (Exception e) {
                log.warn("[FAQ-Analytics] Failed to increment hit count for FAQ {}: {}", faqId, e.getMessage());
            }
        };

        if (faqAnalyticsExecutor != null) {
            try {
                faqAnalyticsExecutor.execute(task);
            } catch (Exception e) {
                log.warn("[FAQ-Analytics] Failed to submit hit-count task to faqAnalyticsExecutor: {}", e.getMessage());
            }
        } else {
            // Fallback: spawn safe daemon thread if executor not yet wired
            Thread daemon = new Thread(task, "faq-hit-fallback");
            daemon.setDaemon(true);
            daemon.start();
        }
    }
}
