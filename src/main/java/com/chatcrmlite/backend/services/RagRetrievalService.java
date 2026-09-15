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
import java.util.Optional;
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
     * Public chat / WhatsApp historically pass owner {@link User#getId()} as businessId.
     * Document/graph RAG is stored under {@link Tenant#getId()}.
     * FAQ rows remain scoped by owner user id.
     */
    record RagScope(User owner, UUID ownerUserId, UUID knowledgeTenantId) {}

    /**
     * Optimized Hybrid Retrieval + LLM Generation with Circuit Breaker,
     * Prompt Injection Defense, and Hallucination detection.
     * Extended with optional Graph RAG via rag.mode (VECTOR|GRAPH|HYBRID).
     *
     * @param businessOrOwnerId owner user id (public chat businessId) or tenant id
     */
    @CircuitBreaker(name = "ragRetrieval", fallbackMethod = "fallbackResponse")
    public String getAiResponse(ConversationContext context, UUID businessOrOwnerId) {
        if (aiOrchestrator == null) {
            return "AI feature is currently being configured. Please check back later.";
        }

        String query = context.getLatestQuery();
        long start = System.currentTimeMillis();
        RagMode mode = RagMode.from(ragModeProperty);

        RagScope scope = resolveRagScope(businessOrOwnerId);
        User user = scope.owner();
        UUID ownerUserId = scope.ownerUserId();
        UUID knowledgeTenantId = scope.knowledgeTenantId();

        quotaService.checkAndEnforceQuota(ownerUserId, user.getPlanType());

        dev.langchain4j.data.embedding.Embedding embedding = embeddingModel.embed(query).content();
        float[] queryEmbedding = embedding.vector();

        // FAQs are stored with owner user id (FaqController uses user.getId())
        FaqMatchingService.MatchResult faqMatch = faqMatchingService.findBestMatch(ownerUserId, query, queryEmbedding);
        if (faqMatch.isHighConfidence() && faqMatch.getFaqItem() != null) {
            log.info("[FAQ-FastPath] High-confidence match (Score: {}) for ownerUserId={} knowledgeTenantId={} | Direct FAQ response returned.",
                    String.format("%.4f", faqMatch.getScore()), ownerUserId, knowledgeTenantId);
            return ChatResponseFormatter.forChatWidget(faqMatch.getFaqItem().getAnswer());
        }

        String cacheQuery = cacheKey(query, mode);
        // Cache keyed by knowledge tenant so it aligns with document/graph corpus
        String cachedResponse = semanticCacheService.getCachedResponse(cacheQuery, queryEmbedding, knowledgeTenantId);
        if (cachedResponse != null) {
            if (ChatResponseFormatter.looksRobotic(cachedResponse)) {
                log.info("[RAG] Skipping robotic cache hit — regenerating for query: {}", query);
            } else {
                return ChatResponseFormatter.forChatWidget(cachedResponse);
            }
        }

        // Document chunks + Neo4j graph use real tenant id (RagController upload path)
        HybridRetrievalResult retrieval = hybridRetrievalService.retrieve(
                query, knowledgeTenantId, queryEmbedding, context.isRequiresRag(), 8);
        FusedContext fused = contextFusionService.fuse(retrieval);
        List<String> chunks = fused.asFlatChunks();

        if ((chunks == null || chunks.isEmpty() || fused.getContextCharCount() == 0)
                && context.isRequiresRag()) {
            log.info("[RAG] No evidence for knowledgeTenantId={} ownerUserId={} mode={} — persona fallback.",
                    knowledgeTenantId, ownerUserId, mode);
        }

        String niche = user.getBusinessType();
        String tenantPersona = null;
        if (user.getTenant() != null && user.getTenant().getId() != null) {
            Tenant tenant = tenantRepository.findById(user.getTenant().getId()).orElse(null);
            if (tenant != null) {
                tenantPersona = tenant.getAiPersonaPrompt();
            }
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
                .tenantId(ownerUserId)
                .complexity(AiRequest.TaskComplexity.HIGH)
                .build();

        AiResponse aiResponse = aiOrchestrator.execute(aiRequest);
        long llmMs = System.currentTimeMillis() - llmStart;
        if (aiResponse == null || aiResponse.getContent() == null) {
            return null;
        }
        String response = aiResponse.getContent();

        String contextString = String.join("\n", chunks != null ? chunks : List.of());
        HallucinationCheckResult guardResult = hallucinationDetector.check(response, contextString, ownerUserId, query);
        if (guardResult != HallucinationCheckResult.GROUNDED && guardResult != HallucinationCheckResult.GROUNDED_REFUSAL) {
            log.warn("[RAG] Response rejected by HallucinationDetector for query: {}. Result: {}", query, guardResult);
            return null;
        }

        response = ChatResponseFormatter.forChatWidget(response);

        if (aiResponse.getTokensUsed() > 0) {
            int totalTokens = aiResponse.getTokensUsed();
            tokenBudgetService.recordTokenUsage(ownerUserId, totalTokens, 0);
            costTracker.trackCost(totalTokens, 0, ownerUserId);
        }

        long latency = System.currentTimeMillis() - start;
        log.info("[RAG] Success | ownerUserId={} knowledgeTenantId={} | Mode: {} | Latency: {}ms | Provider: {} | "
                        + "analysisMs={} vectorMs={} graphMs={} fusionMs={} promptMs={} llmMs={} "
                        + "contextChars={} vectorHits={} graphHits={} vectorDegraded={} graphDegraded={}",
                ownerUserId, knowledgeTenantId, mode, latency, aiResponse.getProvider(),
                retrieval.getAnalysisLatencyMs(), retrieval.getVectorLatencyMs(), retrieval.getGraphLatencyMs(),
                fused.getFusionLatencyMs(), promptMs, llmMs,
                fused.getContextCharCount(),
                retrieval.getVectorResults() != null ? retrieval.getVectorResults().size() : 0,
                retrieval.getGraphResults() != null ? retrieval.getGraphResults().size() : 0,
                retrieval.isVectorDegraded(), retrieval.isGraphDegraded());

        semanticCacheService.putCachedResponse(cacheQuery, queryEmbedding, response, knowledgeTenantId);
        return response;
    }

    @CircuitBreaker(name = "ragRetrieval", fallbackMethod = "fallbackVoiceResponse")
    public String getVoiceAiResponse(ConversationContext context, UUID businessOrOwnerId, String languageMode) {
        if (aiOrchestrator == null) {
            return "Hello! Connecting to assistant, please hold on.";
        }

        String query = context.getLatestQuery();
        long start = System.currentTimeMillis();
        RagMode mode = RagMode.from(ragModeProperty);

        RagScope scope = resolveRagScope(businessOrOwnerId);
        User user = scope.owner();
        UUID ownerUserId = scope.ownerUserId();
        UUID knowledgeTenantId = scope.knowledgeTenantId();

        quotaService.checkAndEnforceQuota(ownerUserId, user.getPlanType());

        dev.langchain4j.data.embedding.Embedding embedding = embeddingModel.embed(query).content();
        float[] queryEmbedding = embedding.vector();

        FaqMatchingService.MatchResult faqMatch = faqMatchingService.findBestMatch(ownerUserId, query, queryEmbedding);
        if (faqMatch.isHighConfidence() && faqMatch.getFaqItem() != null) {
            log.info("[Voice-FAQ] High-confidence match (Score: {}) ownerUserId={} knowledgeTenantId={}",
                    String.format("%.4f", faqMatch.getScore()), ownerUserId, knowledgeTenantId);
            return faqMatch.getFaqItem().getAnswer();
        }

        String cacheQuery = cacheKey(query, mode);
        String cachedResponse = semanticCacheService.getCachedResponse(cacheQuery, queryEmbedding, knowledgeTenantId);
        if (cachedResponse != null && !ChatResponseFormatter.looksRobotic(cachedResponse)) {
            return cachedResponse;
        }

        HybridRetrievalResult retrieval = hybridRetrievalService.retrieve(
                query, knowledgeTenantId, queryEmbedding, context.isRequiresRag(), 4);
        FusedContext fused = contextFusionService.fuse(retrieval);
        List<String> chunks = fused.asFlatChunks();

        String niche = user.getBusinessType();
        String tenantPersona = null;
        String assistantName = "Assistant";
        if (user.getTenant() != null && user.getTenant().getId() != null) {
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
                .tenantId(ownerUserId)
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
            tokenBudgetService.recordTokenUsage(ownerUserId, totalTokens, 0);
            costTracker.trackCost(totalTokens, 0, ownerUserId);
        }

        long latency = System.currentTimeMillis() - start;
        log.info("[Voice-RAG] Success in {}ms | Mode: {} | Provider: {} | ownerUserId={} knowledgeTenantId={} | "
                        + "vectorMs={} graphMs={} fusionMs={}",
                latency, mode, aiResponse.getProvider(), ownerUserId, knowledgeTenantId,
                retrieval.getVectorLatencyMs(), retrieval.getGraphLatencyMs(), fused.getFusionLatencyMs());

        semanticCacheService.putCachedResponse(cacheQuery, queryEmbedding, response, knowledgeTenantId);
        return response;
    }

    /**
     * Accepts public-chat businessId (owner user id) or a real tenants.id.
     */
    RagScope resolveRagScope(UUID businessOrOwnerId) {
        if (businessOrOwnerId == null) {
            throw new IllegalArgumentException("businessOrOwnerId is required");
        }

        Optional<User> asUser = userRepository.findById(businessOrOwnerId);
        if (asUser.isPresent()) {
            User owner = asUser.get();
            UUID knowledgeTenantId = (owner.getTenant() != null && owner.getTenant().getId() != null)
                    ? owner.getTenant().getId()
                    : owner.getId();
            return new RagScope(owner, owner.getId(), knowledgeTenantId);
        }

        Optional<Tenant> asTenant = tenantRepository.findById(businessOrOwnerId);
        if (asTenant.isPresent()) {
            Tenant tenant = asTenant.get();
            User owner = userRepository.findFirstByTenantIdAndRole(tenant.getId(), User.Role.SUPER_ADMIN)
                    .or(() -> userRepository.findFirstByTenantIdAndRole(tenant.getId(), User.Role.OWNER))
                    .or(() -> userRepository.findFirstByTenantIdAndRole(tenant.getId(), User.Role.ADMIN))
                    .or(() -> userRepository.findAllByTenant(tenant).stream().findFirst())
                    .orElseThrow(() -> new RuntimeException("No users found for tenant " + tenant.getId()));
            return new RagScope(owner, owner.getId(), tenant.getId());
        }

        throw new RuntimeException("Tenant/owner not found: " + businessOrOwnerId);
    }

    /** Bump when chat UX / formatting rules change so exact-cache misses old robotic replies. */
    private static final String CHAT_UX_CACHE_VERSION = "ux3";

    private String cacheKey(String query, RagMode mode) {
        String q = CHAT_UX_CACHE_VERSION + ":" + query;
        if (mode == RagMode.VECTOR) {
            return q;
        }
        // Include mode + graph index version so hybrid/graph cache never leaks across graph rebuilds
        return mode.name() + ":v" + graphIndexVersion + ":" + q;
    }

    public String fallbackResponse(ConversationContext context, UUID tenantId, Throwable t) {
        log.error("[RAG-Fallback] Circuit breaker triggered for query: {}. Error: {}", context.getLatestQuery(), t.getMessage());
        return "I'm having a little trouble right now. Please try again in a moment.";
    }

    public String fallbackVoiceResponse(ConversationContext context, UUID tenantId, String languageMode, Throwable t) {
        log.error("[Voice-RAG-Fallback] Circuit breaker triggered for voice query: {}. Error: {}", context.getLatestQuery(), t.getMessage());
        return "I am having trouble connecting right now. Please try again in a moment.";
    }
}
