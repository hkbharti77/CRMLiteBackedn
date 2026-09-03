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
import com.chatcrmlite.backend.dto.memory.ConversationContext;

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
    public String getAiResponse(ConversationContext context, UUID tenantId) {
        if (aiOrchestrator == null) {
            return "AI feature is currently being configured. Please check back later.";
        }
        
        String query = context.getLatestQuery();

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

        // 4. Hybrid Retrieval (Vector + BM25) if RAG is required
        List<String> chunks = null;
        if (context.isRequiresRag()) {
            int topK = 8;
            chunks = hybridSearchService.hybridSearch(tenantId, queryEmbedding, query, topK);

            if (chunks == null || chunks.isEmpty()) {
                log.info("[RAG] No document chunks found for tenant {} and query '{}'. Falling back to persona-based LLM generation.", tenantId, query);
            }
        } else {
            log.info("[RAG-Router] RAG skipped for tenant {} and query '{}'. Reason: Router determined NO_RAG.", tenantId, query);
        }

        // 5. Build Structured Prompt (Injection Resistant + Layered Persona)
        String niche = user.getBusinessType();
        String tenantPersona = null;
        Tenant tenant = tenantRepository.findById(user.getTenant().getId()).orElse(null);
        if (tenant != null) {
            tenantPersona = tenant.getAiPersonaPrompt();
        }
        String prompt = promptBuilder.buildRagPrompt(context, chunks, niche, tenantPersona);

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

    /**
     * Dedicated High-Speed Voice RAG Gate.
     * Uses human spoken prompt, limits token output to max 85 tokens (1-2 sentences),
     * and delivers conversational responses with sub-second LLM latency.
     */
    @CircuitBreaker(name = "ragRetrieval", fallbackMethod = "fallbackVoiceResponse")
    public String getVoiceAiResponse(ConversationContext context, UUID tenantId, String languageMode) {
        if (aiOrchestrator == null) {
            return "Hello! Connecting to assistant, please hold on.";
        }
        
        String query = context.getLatestQuery();

        long start = System.currentTimeMillis();

        // 1. Quota Check
        User user = userRepository.findById(tenantId).orElseThrow(() -> new RuntimeException("Tenant not found"));
        quotaService.checkAndEnforceQuota(tenantId, user.getPlanType());

        // 2. Generate Query Embedding
        dev.langchain4j.data.embedding.Embedding embedding = embeddingModel.embed(query).content();
        float[] queryEmbedding = embedding.vector();

        // 2b. FAQ High-Confidence Fast Path
        FaqMatchingService.MatchResult faqMatch = faqMatchingService.findBestMatch(tenantId, query, queryEmbedding);
        if (faqMatch.isHighConfidence() && faqMatch.getFaqItem() != null) {
            log.info("[Voice-FAQ] High-confidence match (Score: {}) for tenant {}",
                    String.format("%.4f", faqMatch.getScore()), tenantId);
            return faqMatch.getFaqItem().getAnswer();
        }

        // 3. Semantic Cache Check
        String cachedResponse = semanticCacheService.getCachedResponse(query, queryEmbedding, tenantId);
        if (cachedResponse != null) {
            return cachedResponse;
        }

        // 4. Fast Retrieval if RAG is required
        List<String> chunks = null;
        if (context.isRequiresRag()) {
            int topK = 4;
            chunks = hybridSearchService.hybridSearch(tenantId, queryEmbedding, query, topK);
        } else {
            log.info("[Voice-RAG-Router] RAG skipped for tenant {} and query '{}'", tenantId, query);
        }

        // 5. Build Dedicated Spoken-First Voice Prompt with Tenant Voice Persona
        String niche = user.getBusinessType();
        String tenantPersona = null;
        String assistantName = "Priya";
        if (user.getTenant() != null) {
            Tenant tenant = tenantRepository.findById(user.getTenant().getId()).orElse(null);
            if (tenant != null) {
                tenantPersona = (tenant.getVoicePersonaPrompt() != null && !tenant.getVoicePersonaPrompt().isBlank())
                        ? tenant.getVoicePersonaPrompt()
                        : tenant.getAiPersonaPrompt();
                if (tenant.getVoiceAssistantName() != null && !tenant.getVoiceAssistantName().isBlank()) {
                    assistantName = tenant.getVoiceAssistantName();
                }
            }
        }
        String prompt = promptBuilder.buildVoiceRagPrompt(context, chunks, niche, tenantPersona, assistantName, languageMode);

        // 6. Fast LLM Routing with max 85 tokens (< 800ms)
        AiRequest aiRequest = AiRequest.builder()
                .prompt(prompt)
                .tenantId(tenantId)
                .complexity(AiRequest.TaskComplexity.LOW)
                .maxTokens(85)
                .temperature(0.4)
                .build();

        AiResponse aiResponse = aiOrchestrator.execute(aiRequest);
        if (aiResponse == null || aiResponse.getContent() == null) {
            return null;
        }
        String response = aiResponse.getContent();

        // 7. Track Usage
        if (aiResponse.getTokensUsed() > 0) {
            int totalTokens = aiResponse.getTokensUsed();
            tokenBudgetService.recordTokenUsage(tenantId, totalTokens, 0);
            costTracker.trackCost(totalTokens, 0, tenantId);
        }

        long latency = System.currentTimeMillis() - start;
        log.info("[Voice-RAG] Success in {}ms | Provider: {} | Tenant: {}", latency, aiResponse.getProvider(), tenantId);

        // 8. Cache response
        semanticCacheService.putCachedResponse(query, queryEmbedding, response, tenantId);

        return response;
    }

    public String fallbackResponse(ConversationContext context, UUID tenantId, Throwable t) {
        log.error("[RAG-Fallback] Circuit breaker triggered for query: {}. Error: {}", context.getLatestQuery(), t.getMessage());
        return "I'm having trouble connecting to my knowledge base right now. Please try again later.";
    }

    public String fallbackVoiceResponse(ConversationContext context, UUID tenantId, String languageMode, Throwable t) {
        log.error("[Voice-RAG-Fallback] Circuit breaker triggered for voice query: {}. Error: {}", context.getLatestQuery(), t.getMessage());
        return "I am having trouble connecting right now. Please try again in a moment.";
    }
}
