package com.chatcrmlite.backend.services.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class AiOrchestratorTest {

    @Mock
    private AiProvider primaryProvider;

    @Mock
    private AiProvider fallbackProvider;

    @Mock
    private ModelHealthMonitor healthMonitor;

    private AiOrchestrator orchestrator;

    private UUID tenantId;
    private AiRequest testRequest;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        testRequest = AiRequest.builder()
                .prompt("What are your business hours?")
                .tenantId(tenantId)
                .complexity(AiRequest.TaskComplexity.HIGH)
                .build();

        orchestrator = new AiOrchestrator(List.of(primaryProvider, fallbackProvider), healthMonitor);
    }

    @Test
    void testExecute_PrimaryProviderSucceeds_ReturnsResponseAndRecordsSuccess() {
        when(primaryProvider.isHealthy()).thenReturn(true);
        when(primaryProvider.getModelName()).thenReturn("gemini-1.5-flash");
        when(primaryProvider.getCostPer1kTokens()).thenReturn(0.00015);

        when(fallbackProvider.isHealthy()).thenReturn(true);
        when(fallbackProvider.getModelName()).thenReturn("openrouter-backup");
        when(fallbackProvider.getCostPer1kTokens()).thenReturn(0.00050);

        when(healthMonitor.getLatencyP95("gemini-1.5-flash")).thenReturn(200L);
        when(healthMonitor.getLatencyP95("openrouter-backup")).thenReturn(500L);

        AiResponse mockResponse = AiResponse.builder()
                .content("We are open Mon-Fri 9am to 6pm.")
                .tokensUsed(45)
                .latencyMs(180)
                .provider("gemini")
                .build();

        when(primaryProvider.generate(testRequest)).thenReturn(mockResponse);

        AiResponse result = orchestrator.execute(testRequest);

        assertNotNull(result);
        assertEquals("We are open Mon-Fri 9am to 6pm.", result.getContent());
        assertEquals("gemini", result.getProvider());
        verify(primaryProvider).generate(testRequest);
        verify(healthMonitor).recordSuccess("gemini-1.5-flash", 180);
        verify(fallbackProvider, never()).generate(any());
    }

    @Test
    void testExecute_PrimaryProviderFails_FallsBackToSecondaryProvider() {
        when(primaryProvider.isHealthy()).thenReturn(true);
        when(primaryProvider.getModelName()).thenReturn("gemini-1.5-flash");
        when(primaryProvider.getCostPer1kTokens()).thenReturn(0.00015);

        when(fallbackProvider.isHealthy()).thenReturn(true);
        when(fallbackProvider.getModelName()).thenReturn("openrouter-backup");
        when(fallbackProvider.getCostPer1kTokens()).thenReturn(0.00050);

        when(healthMonitor.getLatencyP95("gemini-1.5-flash")).thenReturn(200L);
        when(healthMonitor.getLatencyP95("openrouter-backup")).thenReturn(500L);

        when(primaryProvider.generate(testRequest)).thenThrow(new RuntimeException("503 Service Unavailable"));

        AiResponse fallbackResponse = AiResponse.builder()
                .content("Fallback response: Open Mon-Fri 9am to 6pm.")
                .tokensUsed(48)
                .latencyMs(350)
                .provider("openrouter")
                .build();

        when(fallbackProvider.generate(testRequest)).thenReturn(fallbackResponse);

        AiResponse result = orchestrator.execute(testRequest);

        assertNotNull(result);
        assertEquals("Fallback response: Open Mon-Fri 9am to 6pm.", result.getContent());
        assertEquals("openrouter", result.getProvider());
        verify(healthMonitor).recordFailure("gemini-1.5-flash");
        verify(fallbackProvider).generate(testRequest);
    }

    @Test
    void testExecute_AllProvidersFail_ThrowsRuntimeException() {
        when(primaryProvider.isHealthy()).thenReturn(true);
        when(primaryProvider.getModelName()).thenReturn("gemini-1.5-flash");
        when(primaryProvider.getCostPer1kTokens()).thenReturn(0.00015);

        when(fallbackProvider.isHealthy()).thenReturn(true);
        when(fallbackProvider.getModelName()).thenReturn("openrouter-backup");
        when(fallbackProvider.getCostPer1kTokens()).thenReturn(0.00050);

        when(healthMonitor.getLatencyP95(anyString())).thenReturn(200L);

        when(primaryProvider.generate(testRequest)).thenThrow(new RuntimeException("Primary failure"));
        when(fallbackProvider.generate(testRequest)).thenThrow(new RuntimeException("Fallback failure"));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> orchestrator.execute(testRequest));
        assertTrue(ex.getMessage().contains("Fallback failed") || ex.getMessage().contains("Fallback failure"));
        verify(healthMonitor).recordFailure("gemini-1.5-flash");
    }

    @Test
    void testExecute_NoHealthyProviders_ThrowsRuntimeException() {
        when(primaryProvider.isHealthy()).thenReturn(false);
        when(fallbackProvider.isHealthy()).thenReturn(false);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> orchestrator.execute(testRequest));
        assertTrue(ex.getMessage().contains("No healthy AI providers available"));
    }
}
