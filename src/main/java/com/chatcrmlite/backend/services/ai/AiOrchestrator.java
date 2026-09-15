package com.chatcrmlite.backend.services.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Slf4j
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
        if (providers == null || providers.isEmpty()) {
            throw new IllegalStateException("No AI providers configured in Spring context!");
        }

        return providers.stream()
                .filter(AiProvider::isHealthy)
                .min(Comparator.comparingDouble(p -> calculateScore(p, request)))
                .orElseGet(() -> {
                    log.warn("⚠️ [AI-Orchestrator] All providers circuit-open/unhealthy. Falling back to primary provider: {}", providers.get(0).getModelName());
                    return providers.get(0);
                });
    }

    private double calculateScore(AiProvider provider, AiRequest request) {
        // Lower is better
        double costScore = provider.getCostPer1kTokens();
        double healthPenalty = healthMonitor.getLatencyP95(provider.getModelName()) / 1000.0;
        
        return costScore + healthPenalty;
    }

    private AiResponse fallback(AiRequest request, AiProvider failedProvider) {
        return providers.stream()
                .filter(p -> !p.equals(failedProvider))
                .findFirst()
                .map(p -> {
                    log.info("🔄 [AI-Orchestrator] Falling back to provider: {}", p.getModelName());
                    return p.generate(request);
                })
                .orElseGet(() -> {
                    log.warn("⚠️ [AI-Orchestrator] No alternative providers available. Attempting direct execution on {}", failedProvider.getModelName());
                    return failedProvider.generate(request);
                });
    }
}
