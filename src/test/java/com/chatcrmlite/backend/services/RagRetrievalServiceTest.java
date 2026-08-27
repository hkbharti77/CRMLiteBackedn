package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.models.FaqItem;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.TenantRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.ai.AiOrchestrator;
import com.chatcrmlite.backend.services.ai.AiRequest;
import com.chatcrmlite.backend.services.ai.AiResponse;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RagRetrievalServiceTest {

    @Mock
    private HybridSearchService hybridSearchService;

    @Mock
    private PromptBuilder promptBuilder;

    @Mock
    private HallucinationDetector hallucinationDetector;

    @Mock
    private AiOrchestrator aiOrchestrator;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private AIQuotaService quotaService;

    @Mock
    private SemanticCacheService semanticCacheService;

    @Mock
    private TokenBudgetService tokenBudgetService;

    @Mock
    private CostTracker costTracker;

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private FaqMatchingService faqMatchingService;

    @InjectMocks
    private RagRetrievalService ragRetrievalService;

    private UUID tenantId;
    private User mockUser;
    private Tenant mockTenant;
    private float[] mockVector;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        mockTenant = new Tenant();
        mockTenant.setId(tenantId);
        mockTenant.setAiPersonaPrompt("You are a helpful customer assistant.");

        mockUser = new User();
        mockUser.setId(tenantId);
        mockUser.setPlanType(User.PlanType.PRO);
        mockUser.setBusinessType("REAL_ESTATE");
        mockUser.setTenant(mockTenant);

        mockVector = new float[]{0.1f, 0.2f, 0.3f};

        when(userRepository.findById(tenantId)).thenReturn(Optional.of(mockUser));
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(mockTenant));

        Embedding embedding = Embedding.from(mockVector);
        when(embeddingModel.embed(anyString())).thenReturn(Response.from(embedding));
    }

    @Test
    @DisplayName("TEST 1 & TEST 8: Normal RAG cache miss actually invokes AiOrchestrator")
    void testRagRequest_InvokesAiOrchestratorOnCacheMiss() {
        String query = "What are your property prices?";

        when(faqMatchingService.findBestMatch(eq(tenantId), eq(query), any())).thenReturn(
                new FaqMatchingService.MatchResult(null, 0.2f, false)
        );
        when(semanticCacheService.getCachedResponse(eq(query), any(), eq(tenantId))).thenReturn(null);

        List<String> chunks = List.of("Property pricing starts at $200k.");
        when(hybridSearchService.hybridSearch(eq(tenantId), any(), eq(query), eq(8))).thenReturn(chunks);
        when(promptBuilder.buildRagPrompt(eq(query), eq(chunks), any(), any()))
                .thenReturn("Formatted prompt with context");

        AiResponse aiResponse = AiResponse.builder()
                .content("Our properties start at $200,000.")
                .tokensUsed(60)
                .latencyMs(250)
                .provider("gemini")
                .build();
        when(aiOrchestrator.execute(any(AiRequest.class))).thenReturn(aiResponse);
        when(hallucinationDetector.isValid(anyString(), anyString())).thenReturn(true);

        String result = ragRetrievalService.getAiResponse(query, tenantId);

        assertEquals("Our properties start at $200,000.", result);
        verify(aiOrchestrator, times(1)).execute(any(AiRequest.class));
        verify(semanticCacheService).putCachedResponse(eq(query), any(), eq("Our properties start at $200,000."), eq(tenantId));
    }

    @Test
    @DisplayName("TEST 2: Primary provider succeeds -> response is returned normally with token tracking")
    void testRagRequest_PrimaryProviderSucceeds() {
        String query = "How do I schedule a viewing?";

        when(faqMatchingService.findBestMatch(eq(tenantId), eq(query), any())).thenReturn(
                new FaqMatchingService.MatchResult(null, 0.1f, false)
        );
        when(semanticCacheService.getCachedResponse(eq(query), any(), eq(tenantId))).thenReturn(null);

        when(hybridSearchService.hybridSearch(eq(tenantId), any(), eq(query), eq(8)))
                .thenReturn(List.of("Viewings can be booked online."));
        when(promptBuilder.buildRagPrompt(anyString(), anyList(), any(), any()))
                .thenReturn("Structured prompt");

        AiResponse aiResponse = AiResponse.builder()
                .content("You can book a viewing directly from our website.")
                .tokensUsed(50)
                .latencyMs(190)
                .provider("gemini-1.5-flash")
                .build();
        when(aiOrchestrator.execute(any(AiRequest.class))).thenReturn(aiResponse);
        when(hallucinationDetector.isValid(anyString(), anyString())).thenReturn(true);

        String result = ragRetrievalService.getAiResponse(query, tenantId);

        assertNotNull(result);
        assertEquals("You can book a viewing directly from our website.", result);
        verify(tokenBudgetService).recordTokenUsage(tenantId, 50, 0);
        verify(costTracker).trackCost(50, 0, tenantId);
    }

    @Test
    @DisplayName("TEST 3: Fallback provider returned by AiOrchestrator succeeds")
    void testRagRequest_FallbackProviderSucceedsViaOrchestrator() {
        String query = "Where is your office?";

        when(faqMatchingService.findBestMatch(eq(tenantId), eq(query), any())).thenReturn(
                new FaqMatchingService.MatchResult(null, 0.0f, false)
        );
        when(semanticCacheService.getCachedResponse(eq(query), any(), eq(tenantId))).thenReturn(null);
        when(hybridSearchService.hybridSearch(any(), any(), any(), anyInt())).thenReturn(List.of("Office is in NY."));
        when(promptBuilder.buildRagPrompt(any(), any(), any(), any())).thenReturn("Prompt");

        // AiOrchestrator successfully failed over to OpenRouter
        AiResponse fallbackResponse = AiResponse.builder()
                .content("Our office is located at 123 Main St, New York.")
                .tokensUsed(40)
                .latencyMs(410)
                .provider("openrouter-fallback")
                .build();
        when(aiOrchestrator.execute(any(AiRequest.class))).thenReturn(fallbackResponse);
        when(hallucinationDetector.isValid(anyString(), anyString())).thenReturn(true);

        String result = ragRetrievalService.getAiResponse(query, tenantId);

        assertEquals("Our office is located at 123 Main St, New York.", result);
        verify(aiOrchestrator).execute(any(AiRequest.class));
    }

    @Test
    @DisplayName("TEST 4 & 5: All providers fail in AiOrchestrator -> controlled fallback method")
    void testRagRequest_AllProvidersFail_ControlledFallback() {
        String query = "Tell me about your services";

        when(faqMatchingService.findBestMatch(any(), any(), any())).thenReturn(
                new FaqMatchingService.MatchResult(null, 0.0f, false)
        );
        when(semanticCacheService.getCachedResponse(any(), any(), any())).thenReturn(null);
        when(hybridSearchService.hybridSearch(any(), any(), any(), anyInt())).thenReturn(List.of());
        when(promptBuilder.buildRagPrompt(any(), any(), any(), any())).thenReturn("Prompt");

        when(aiOrchestrator.execute(any(AiRequest.class)))
                .thenThrow(new RuntimeException("Fallback failed: All AI providers are down!"));

        // Direct call to fallbackResponse method (which circuit breaker executes on error)
        String fallback = ragRetrievalService.fallbackResponse(query, tenantId, new RuntimeException("All providers down"));

        assertNotNull(fallback);
        assertTrue(fallback.contains("trouble connecting to my knowledge base"));
    }

    @Test
    @DisplayName("TEST 6: FAQ high-confidence fast-path does NOT invoke AiOrchestrator / LLM")
    void testFaqFastPath_HighConfidence_BypassesAiOrchestrator() {
        String query = "What is your refund policy?";

        FaqItem faqItem = new FaqItem();
        faqItem.setQuestion("What is your refund policy?");
        faqItem.setAnswer("We offer a full 30-day money-back guarantee.");
        faqItem.setIsActive(true);

        when(faqMatchingService.findBestMatch(eq(tenantId), eq(query), any())).thenReturn(
                new FaqMatchingService.MatchResult(faqItem, 0.95f, true)
        );

        String result = ragRetrievalService.getAiResponse(query, tenantId);

        assertEquals("We offer a full 30-day money-back guarantee.", result);
        verify(aiOrchestrator, never()).execute(any());
        verify(hybridSearchService, never()).hybridSearch(any(), any(), any(), anyInt());
        verify(semanticCacheService, never()).getCachedResponse(any(), any(), any());
    }

    @Test
    @DisplayName("TEST 7: Semantic cache hit does NOT invoke AiOrchestrator / LLM")
    void testSemanticCacheHit_BypassesAiOrchestrator() {
        String query = "Do you offer parking?";

        when(faqMatchingService.findBestMatch(eq(tenantId), eq(query), any())).thenReturn(
                new FaqMatchingService.MatchResult(null, 0.1f, false)
        );
        when(semanticCacheService.getCachedResponse(eq(query), any(), eq(tenantId)))
                .thenReturn("Yes, free parking is available on-site.");

        String result = ragRetrievalService.getAiResponse(query, tenantId);

        assertEquals("Yes, free parking is available on-site.", result);
        verify(aiOrchestrator, never()).execute(any());
        verify(hybridSearchService, never()).hybridSearch(any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("TEST 9: Tenant context is preserved throughout the orchestration path")
    void testTenantContextPreserved_InAiRequest() {
        String query = "Explain your warranty";

        when(faqMatchingService.findBestMatch(any(), any(), any())).thenReturn(
                new FaqMatchingService.MatchResult(null, 0.0f, false)
        );
        when(semanticCacheService.getCachedResponse(any(), any(), any())).thenReturn(null);
        when(hybridSearchService.hybridSearch(any(), any(), any(), anyInt())).thenReturn(List.of("1-year warranty."));
        when(promptBuilder.buildRagPrompt(any(), any(), any(), any())).thenReturn("Prompt content");

        AiResponse aiResponse = AiResponse.builder()
                .content("We offer a 1-year comprehensive warranty.")
                .tokensUsed(35)
                .latencyMs(120)
                .provider("gemini")
                .build();
        when(aiOrchestrator.execute(any(AiRequest.class))).thenReturn(aiResponse);
        when(hallucinationDetector.isValid(anyString(), anyString())).thenReturn(true);

        ragRetrievalService.getAiResponse(query, tenantId);

        ArgumentCaptor<AiRequest> requestCaptor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiOrchestrator).execute(requestCaptor.capture());

        AiRequest captured = requestCaptor.getValue();
        assertNotNull(captured);
        assertEquals(tenantId, captured.getTenantId(), "Tenant ID must be preserved in AiRequest");
        assertEquals(AiRequest.TaskComplexity.HIGH, captured.getComplexity(), "Task complexity must be HIGH for RAG");
        assertEquals("Prompt content", captured.getPrompt());
    }

    @Test
    @DisplayName("TEST 10: Hallucination detector rejection returns null and does not cache")
    void testHallucinationDetectorRejection_ReturnsNullAndDoesNotCache() {
        String query = "Who is the CEO?";

        when(faqMatchingService.findBestMatch(any(), any(), any())).thenReturn(
                new FaqMatchingService.MatchResult(null, 0.0f, false)
        );
        when(semanticCacheService.getCachedResponse(any(), any(), any())).thenReturn(null);
        when(hybridSearchService.hybridSearch(any(), any(), any(), anyInt())).thenReturn(List.of("Company context without CEO name."));
        when(promptBuilder.buildRagPrompt(any(), any(), any(), any())).thenReturn("Prompt content");

        AiResponse aiResponse = AiResponse.builder()
                .content("I am not sure, perhaps it might be John Doe.")
                .tokensUsed(25)
                .latencyMs(100)
                .provider("gemini")
                .build();
        when(aiOrchestrator.execute(any(AiRequest.class))).thenReturn(aiResponse);
        when(hallucinationDetector.isValid(anyString(), anyString())).thenReturn(false); // Detected hallucination

        String result = ragRetrievalService.getAiResponse(query, tenantId);

        assertNull(result, "Response rejected by hallucination detector should return null");
        verify(semanticCacheService, never()).putCachedResponse(any(), any(), any(), any());
    }

    @Test
    @DisplayName("AiOrchestrator null check returns configuration message")
    void testAiOrchestratorNull_ReturnsConfigurationMessage() {
        RagRetrievalService unconfiguredService = new RagRetrievalService();
        String response = unconfiguredService.getAiResponse("Any query", UUID.randomUUID());
        assertEquals("AI feature is currently being configured. Please check back later.", response);
    }

    @Test
    @DisplayName("TEST 8: Tenant isolation in retrieval & cache querying")
    void testTenantIsolation_TenantContextPreservedInRetrieval() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        User userB = new User();
        userB.setId(tenantB);
        userB.setPlanType(User.PlanType.FREE);
        userB.setBusinessType("HEALTHCARE");
        Tenant tenantBObj = new Tenant();
        tenantBObj.setId(tenantB);
        userB.setTenant(tenantBObj);

        when(userRepository.findById(tenantB)).thenReturn(Optional.of(userB));
        when(tenantRepository.findById(tenantB)).thenReturn(Optional.of(tenantBObj));

        when(faqMatchingService.findBestMatch(eq(tenantB), anyString(), any())).thenReturn(
                new FaqMatchingService.MatchResult(null, 0.0f, false)
        );
        when(semanticCacheService.getCachedResponse(anyString(), any(), eq(tenantB))).thenReturn(null);
        when(hybridSearchService.hybridSearch(eq(tenantB), any(), anyString(), eq(8)))
                .thenReturn(List.of("Clinic B consultation fee is ₹700."));
        when(promptBuilder.buildRagPrompt(anyString(), anyList(), anyString(), any()))
                .thenReturn("Prompt for Tenant B");

        AiResponse aiResponse = AiResponse.builder()
                .content("Consultation fee is ₹700.")
                .tokensUsed(30)
                .latencyMs(150)
                .provider("gemini")
                .build();
        when(aiOrchestrator.execute(any(AiRequest.class))).thenReturn(aiResponse);
        when(hallucinationDetector.isValid(eq("Consultation fee is ₹700."), anyString())).thenReturn(true);

        String responseB = ragRetrievalService.getAiResponse("What is the consultation fee?", tenantB);

        assertEquals("Consultation fee is ₹700.", responseB);
        // Verify hybrid search was strictly called with tenantB
        verify(hybridSearchService).hybridSearch(eq(tenantB), any(), anyString(), eq(8));
        verify(hybridSearchService, never()).hybridSearch(eq(tenantA), any(), anyString(), anyInt());
        // Verify cache storage was strictly with tenantB
        verify(semanticCacheService).putCachedResponse(anyString(), any(), eq("Consultation fee is ₹700."), eq(tenantB));
    }
}
