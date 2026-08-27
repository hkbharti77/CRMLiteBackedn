package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.models.DocumentChunk;
import com.chatcrmlite.backend.repositories.DocumentChunkRepository;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2q.AllMiniLmL6V2QuantizedEmbeddingModel;
import com.chatcrmlite.backend.services.ai.AiOrchestrator;
import com.chatcrmlite.backend.services.ai.AiRequest;
import com.chatcrmlite.backend.services.ai.AiResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.UserRepository;

import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.repositories.TenantRepository;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RagRetrievalService {

    @Autowired
    private HybridSearchService hybridSearchService;

    @Autowired
    private PromptBuilder promptBuilder;

    @Autowired
    private HallucinationDetector hallucinationDetector;

    @Autowired(required = false)
    private AiOrchestrator aiOrchestrator;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private AIQuotaService quotaService;

    @Autowired
    private SemanticCacheService semanticCacheService;

    @Autowired
    private TokenBudgetService tokenBudgetService;

    @Autowired
    private CostTracker costTracker;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private FaqMatchingService faqMatchingService;

    /**
     * Optimized Hybrid Retrieval + LLM Generation with Circuit Breaker, 
     * Prompt Injection Defense, and Hallucination detection.
     */
    @CircuitBreaker(name = "ragRetrieval", fallbackMethod = "fallbackResponse")
    public String getAiResponse(String query, UUID tenantId) {
        if (aiOrchestrator == null) {
            return "AI feature is currently being configured. Please check back later.";
        }

        long start = System.currentTimeMillis();

        // 1. Quota Check
        User user = userRepository.findById(tenantId).orElseThrow(() -> new RuntimeException("Tenant not found"));
        quotaService.checkAndEnforceQuota(tenantId, user.getPlanType());

        // 2. Generate Query Embedding
        dev.langchain4j.data.embedding.Embedding embedding = embeddingModel.embed(query).content();
        float[] queryEmbedding = embedding.vector();

        // 2b. FAQ High-Confidence Fast Path (Direct Answer if score >= 85%)
        FaqMatchingService.MatchResult faqMatch = faqMatchingService.findBestMatch(tenantId, query, queryEmbedding);
        if (faqMatch.isHighConfidence() && faqMatch.getFaqItem() != null) {
            log.info("[FAQ-FastPath] High-confidence match (Score: {}) for tenant {} | Direct FAQ response returned.",
                    String.format("%.4f", faqMatch.getScore()), tenantId);
            return faqMatch.getFaqItem().getAnswer();
        }

        // 3. Semantic Cache Check (Exact string match < 1ms, Semantic HNSW vector lookup < 5ms)
        String cachedResponse = semanticCacheService.getCachedResponse(query, queryEmbedding, tenantId);
        if (cachedResponse != null) {
            return cachedResponse;
        }

        // 4. Hybrid Retrieval (Vector + BM25)
        int topK = 8;
        List<String> chunks = hybridSearchService.hybridSearch(tenantId, queryEmbedding, query, topK);

        if (chunks == null || chunks.isEmpty()) {
            log.info("[RAG] No document chunks found for tenant {} and query '{}'. Falling back to persona-based LLM generation.", tenantId, query);
        }

        // 5. Build Structured Prompt (Injection Resistant + Layered Persona)
        String niche = user.getBusinessType();
        String tenantPersona = null;
        Tenant tenant = tenantRepository.findById(user.getTenant().getId()).orElse(null);
        if (tenant != null) {
            tenantPersona = tenant.getAiPersonaPrompt();
        }
        String prompt = promptBuilder.buildRagPrompt(query, chunks, niche, tenantPersona);

        // 6. Generate Response via AiOrchestrator (Provider Routing & Fallback)
        AiRequest aiRequest = AiRequest.builder()
                .prompt(prompt)
                .tenantId(tenantId)
                .complexity(AiRequest.TaskComplexity.HIGH)
                .build();

        AiResponse aiResponse = aiOrchestrator.execute(aiRequest);
        if (aiResponse == null || aiResponse.getContent() == null) {
            return null;
        }
        String response = aiResponse.getContent();
        
        // 7. Post-generation Hallucination Guard
        String contextString = String.join("\n", chunks != null ? chunks : List.of());
        if (!hallucinationDetector.isValid(response, contextString)) {
            log.warn("[RAG] Response rejected by HallucinationDetector for query: {}", query);
            return null;
        }

        // 8. Track Usage & Costs
        if (aiResponse.getTokensUsed() > 0) {
            int totalTokens = aiResponse.getTokensUsed();
            tokenBudgetService.recordTokenUsage(tenantId, totalTokens, 0);
            costTracker.trackCost(totalTokens, 0, tenantId);
        }

        long latency = System.currentTimeMillis() - start;
        log.info("[RAG] Success | Tenant: {} | Latency: {}ms | Provider: {}", tenantId, latency, aiResponse.getProvider());

        // 9. Cache successful result
        semanticCacheService.putCachedResponse(query, queryEmbedding, response, tenantId);

        return response;
    }

    public String fallbackResponse(String query, UUID tenantId, Throwable t) {
        log.error("[RAG-Fallback] Circuit breaker triggered for query: {}. Error: {}", query, t.getMessage());
        return "I'm having trouble connecting to my knowledge base right now. Please try again later.";
    }
}
