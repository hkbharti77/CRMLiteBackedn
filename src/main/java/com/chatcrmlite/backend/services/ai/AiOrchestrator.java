package com.chatcrmlite.backend.services.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiOrchestrator {

    private final List<AiProvider> providers;
    private final ModelHealthMonitor healthMonitor;

    /**
     * Entry point for all AI requests.
     */
    public AiResponse execute(AiRequest request) {
        // 1. ROUTE to the best provider
        AiProvider selectedProvider = route(request);
        
        try {
            log.info("🚀 [AI-Orchestrator] Routing task to {}", selectedProvider.getModelName());
            AiResponse response = selectedProvider.generate(request);
            
            // Record success for health monitor
            healthMonitor.recordSuccess(selectedProvider.getModelName(), response.getLatencyMs());
            return response;
            
        } catch (Exception e) {
            log.warn("⚠️ [AI-Orchestrator] Provider {} unavailable ({}). Triggering fallback...", selectedProvider.getModelName(), e.getMessage());
            healthMonitor.recordFailure(selectedProvider.getModelName());
            return fallback(request, selectedProvider);
        }
    }

    private AiProvider route(AiRequest request) {
        // Simple strategy:
        // - If complexity is LOW, use the cheapest healthy provider.
        // - If complexity is HIGH, use the most powerful (e.g. Gemini Pro / GPT-4).
        
        return providers.stream()
                .filter(AiProvider::isHealthy)
                .min(Comparator.comparingDouble(p -> calculateScore(p, request)))
                .orElseThrow(() -> new RuntimeException("No healthy AI providers available!"));
    }

    private double calculateScore(AiProvider provider, AiRequest request) {
        // Lower is better
        double costScore = provider.getCostPer1kTokens();
        double healthPenalty = healthMonitor.getLatencyP95(provider.getModelName()) / 1000.0;
        
        return costScore + healthPenalty;
    }

    private AiResponse fallback(AiRequest request, AiProvider failedProvider) {
        // Try the next best healthy provider
        return providers.stream()
                .filter(p -> !p.equals(failedProvider) && p.isHealthy())
                .findFirst()
                .map(p -> p.generate(request))
                .orElseThrow(() -> new RuntimeException("Fallback failed: All AI providers are down!"));
    }
}
