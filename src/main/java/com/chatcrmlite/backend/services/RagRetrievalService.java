package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.models.DocumentChunk;
import com.chatcrmlite.backend.repositories.DocumentChunkRepository;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2q.AllMiniLmL6V2QuantizedEmbeddingModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.UserRepository;

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

    /** 
     * Injected automatically by langchain4j-google-ai-gemini-spring-boot-starter 
     */
    @Autowired(required = false)
    private ChatLanguageModel chatLanguageModel;

    @Autowired
    private UserRepository userRepository;

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
    @CircuitBreaker(name = "aiService", fallbackMethod = "fallbackResponse")
    public String getAiResponse(String query, UUID tenantId) {
        if (chatLanguageModel == null) {
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

        // 3. Semantic Cache Check (O(log N) in DB)
        String cachedResponse = semanticCacheService.getCachedResponse(queryEmbedding, tenantId);
        if (cachedResponse != null) {
            return cachedResponse;
        }

        // 4. Hybrid Retrieval (Vector + BM25)
        int topK = 4;
        List<String> chunks = hybridSearchService.hybridSearch(tenantId, queryEmbedding, query, topK);
        
        if (chunks.isEmpty()) {
            log.info("[RAG] No context found for tenant {} and query '{}'", tenantId, query);
            return null; 
        }

        // 5. Build Structured Prompt (Injection Resistant)
        String niche = user.getBusinessType(); 
        String prompt = promptBuilder.buildRagPrompt(query, chunks, niche);

        // 6. Generate Response
        Response<AiMessage> responseObj = chatLanguageModel.generate(List.of(UserMessage.from(prompt)));
        String response = responseObj.content().text();
        
        // 7. Post-generation Hallucination Guard
        String contextString = String.join("\n", chunks);
        if (!hallucinationDetector.isValid(response, contextString)) {
            log.warn("[RAG] Response rejected by HallucinationDetector for query: {}", query);
            return null;
        }

        // 8. Track Usage & Costs
        if (responseObj.tokenUsage() != null) {
            TokenUsage usage = responseObj.tokenUsage();
            tokenBudgetService.recordTokenUsage(tenantId, usage.inputTokenCount(), usage.outputTokenCount());
            costTracker.trackCost(usage.inputTokenCount(), usage.outputTokenCount(), tenantId);
        }

        long latency = System.currentTimeMillis() - start;
        log.info("[RAG] Success | Tenant: {} | Latency: {}ms", tenantId, latency);

        // 9. Cache successful result
        semanticCacheService.putCachedResponse(query, queryEmbedding, response, tenantId);

        return response;
    }

    public String fallbackResponse(String query, UUID tenantId, Throwable t) {
        log.error("[RAG-Fallback] Circuit breaker triggered for query: {}. Error: {}", query, t.getMessage());
        return "I'm having trouble connecting to my knowledge base right now. Please try again later.";
    }
}
