package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.dto.memory.ConversationContext;
import com.chatcrmlite.backend.dto.rag.FusedContext;
import com.chatcrmlite.backend.dto.rag.HybridRetrievalResult;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.TenantRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.ai.AiOrchestrator;
import com.chatcrmlite.backend.services.ai.AiRequest;
import com.chatcrmlite.backend.services.ai.AiResponse;
import com.chatcrmlite.backend.services.rag.ContextFusionService;
import com.chatcrmlite.backend.services.rag.HybridRetrievalService;
import com.chatcrmlite.backend.services.rag.RagMode;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class RagRetrievalService {

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

    @Autowired
    private HybridRetrievalService hybridRetrievalService;

    @Autowired
    private ContextFusionService contextFusionService;

    @Value("${rag.mode:VECTOR}")
    private String ragModeProperty;

    @Value("${rag.graph.index-version:1}")
    private String graphIndexVersion;

    /**
     * Optimized Hybrid Retrieval + LLM Generation with Circuit Breaker,
     * Prompt Injection Defense, and Hallucination detection.
     * Extended with optional Graph RAG via rag.mode (VECTOR|GRAPH|HYBRID).
     */
    @CircuitBreaker(name = "ragRetrieval", fallbackMethod = "fallbackResponse")
    public String getAiResponse(ConversationContext context, UUID tenantId) {
        if (aiOrchestrator == null) {
            return "AI feature is currently being configured. Please check back later.";
        }

        String query = context.getLatestQuery();
        long start = System.currentTimeMillis();
        RagMode mode = RagMode.from(ragModeProperty);

        User user = userRepository.findById(tenantId).orElseThrow(() -> new RuntimeException("Tenant not found"));
        quotaService.checkAndEnforceQuota(tenantId, user.getPlanType());

        dev.langchain4j.data.embedding.Embedding embedding = embeddingModel.embed(query).content();
        float[] queryEmbedding = embedding.vector();

        FaqMatchingService.MatchResult faqMatch = faqMatchingService.findBestMatch(tenantId, query, queryEmbedding);
        if (faqMatch.isHighConfidence() && faqMatch.getFaqItem() != null) {
            log.info("[FAQ-FastPath] High-confidence match (Score: {}) for tenant {} | Direct FAQ response returned.",
                    String.format("%.4f", faqMatch.getScore()), tenantId);
            return faqMatch.getFaqItem().getAnswer();
        }

        String cacheQuery = cacheKey(query, mode);
        String cachedResponse = semanticCacheService.getCachedResponse(cacheQuery, queryEmbedding, tenantId);
        if (cachedResponse != null) {
            return cachedResponse;
        }

        HybridRetrievalResult retrieval = hybridRetrievalService.retrieve(
                query, tenantId, queryEmbedding, context.isRequiresRag(), 8);
        FusedContext fused = contextFusionService.fuse(retrieval);
        List<String> chunks = fused.asFlatChunks();

        if ((chunks == null || chunks.isEmpty() || fused.getContextCharCount() == 0)
                && context.isRequiresRag()) {
            log.info("[RAG] No evidence for tenant {} mode={} — persona fallback.", tenantId, mode);
        }

        String niche = user.getBusinessType();
        String tenantPersona = null;
        Tenant tenant = tenantRepository.findById(user.getTenant().getId()).orElse(null);
        if (tenant != null) {
            tenantPersona = tenant.getAiPersonaPrompt();
        }

        long promptStart = System.currentTimeMillis();
        String prompt = (mode == RagMode.VECTOR && !retrieval.isGraphDegraded()
                && (retrieval.getGraphResults() == null || retrieval.getGraphResults().isEmpty()))
                ? promptBuilder.buildRagPrompt(context, chunks, niche, tenantPersona)
                : promptBuilder.buildHybridRagPrompt(context, fused, niche, tenantPersona);
        long promptMs = System.currentTimeMillis() - promptStart;

        long llmStart = System.currentTimeMillis();
        AiRequest aiRequest = AiRequest.builder()
                .prompt(prompt)
                .tenantId(tenantId)
                .complexity(AiRequest.TaskComplexity.HIGH)
                .build();

        AiResponse aiResponse = aiOrchestrator.execute(aiRequest);
        long llmMs = System.currentTimeMillis() - llmStart;
        if (aiResponse == null || aiResponse.getContent() == null) {
            return null;
        }
        String response = aiResponse.getContent();

        String contextString = String.join("\n", chunks != null ? chunks : List.of());
        HallucinationCheckResult guardResult = hallucinationDetector.check(response, contextString, tenantId);
        if (guardResult != HallucinationCheckResult.GROUNDED && guardResult != HallucinationCheckResult.GROUNDED_REFUSAL) {
            log.warn("[RAG] Response rejected by HallucinationDetector for query: {}. Result: {}", query, guardResult);
            return null;
        }

        if (aiResponse.getTokensUsed() > 0) {
            int totalTokens = aiResponse.getTokensUsed();
            tokenBudgetService.recordTokenUsage(tenantId, totalTokens, 0);
            costTracker.trackCost(totalTokens, 0, tenantId);
        }

        long latency = System.currentTimeMillis() - start;
        log.info("[RAG] Success | Tenant: {} | Mode: {} | Latency: {}ms | Provider: {} | "
                        + "analysisMs={} vectorMs={} graphMs={} fusionMs={} promptMs={} llmMs={} "
                        + "contextChars={} vectorHits={} graphHits={} vectorDegraded={} graphDegraded={}",
                tenantId, mode, latency, aiResponse.getProvider(),
                retrieval.getAnalysisLatencyMs(), retrieval.getVectorLatencyMs(), retrieval.getGraphLatencyMs(),
                fused.getFusionLatencyMs(), promptMs, llmMs,
                fused.getContextCharCount(),
                retrieval.getVectorResults() != null ? retrieval.getVectorResults().size() : 0,
                retrieval.getGraphResults() != null ? retrieval.getGraphResults().size() : 0,
                retrieval.isVectorDegraded(), retrieval.isGraphDegraded());

        semanticCacheService.putCachedResponse(cacheQuery, queryEmbedding, response, tenantId);
        return response;
    }

    @CircuitBreaker(name = "ragRetrieval", fallbackMethod = "fallbackVoiceResponse")
    public String getVoiceAiResponse(ConversationContext context, UUID tenantId, String languageMode) {
        if (aiOrchestrator == null) {
            return "Hello! Connecting to assistant, please hold on.";
        }

        String query = context.getLatestQuery();
        long start = System.currentTimeMillis();
        RagMode mode = RagMode.from(ragModeProperty);

        User user = userRepository.findById(tenantId).orElseThrow(() -> new RuntimeException("Tenant not found"));
        quotaService.checkAndEnforceQuota(tenantId, user.getPlanType());

        dev.langchain4j.data.embedding.Embedding embedding = embeddingModel.embed(query).content();
        float[] queryEmbedding = embedding.vector();

        FaqMatchingService.MatchResult faqMatch = faqMatchingService.findBestMatch(tenantId, query, queryEmbedding);
        if (faqMatch.isHighConfidence() && faqMatch.getFaqItem() != null) {
            log.info("[Voice-FAQ] High-confidence match (Score: {}) for tenant {}",
                    String.format("%.4f", faqMatch.getScore()), tenantId);
            return faqMatch.getFaqItem().getAnswer();
        }

        String cacheQuery = cacheKey(query, mode);
        String cachedResponse = semanticCacheService.getCachedResponse(cacheQuery, queryEmbedding, tenantId);
        if (cachedResponse != null) {
            return cachedResponse;
        }

        HybridRetrievalResult retrieval = hybridRetrievalService.retrieve(
                query, tenantId, queryEmbedding, context.isRequiresRag(), 4);
        FusedContext fused = contextFusionService.fuse(retrieval);
        List<String> chunks = fused.asFlatChunks();

        String niche = user.getBusinessType();
        String tenantPersona = null;
        String assistantName = "Assistant";
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

        String prompt = (mode == RagMode.VECTOR)
                ? promptBuilder.buildVoiceRagPrompt(context, chunks, niche, tenantPersona, assistantName, languageMode)
                : promptBuilder.buildHybridVoiceRagPrompt(context, fused, niche, tenantPersona, assistantName, languageMode);

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

        if (aiResponse.getTokensUsed() > 0) {
            int totalTokens = aiResponse.getTokensUsed();
            tokenBudgetService.recordTokenUsage(tenantId, totalTokens, 0);
            costTracker.trackCost(totalTokens, 0, tenantId);
        }

        long latency = System.currentTimeMillis() - start;
        log.info("[Voice-RAG] Success in {}ms | Mode: {} | Provider: {} | Tenant: {} | "
                        + "vectorMs={} graphMs={} fusionMs={}",
                latency, mode, aiResponse.getProvider(), tenantId,
                retrieval.getVectorLatencyMs(), retrieval.getGraphLatencyMs(), fused.getFusionLatencyMs());

        semanticCacheService.putCachedResponse(cacheQuery, queryEmbedding, response, tenantId);
        return response;
    }

    private String cacheKey(String query, RagMode mode) {
        if (mode == RagMode.VECTOR) {
            return query;
        }
        // Include mode + graph index version so hybrid/graph cache never leaks across graph rebuilds
        return mode.name() + ":v" + graphIndexVersion + ":" + query;
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
