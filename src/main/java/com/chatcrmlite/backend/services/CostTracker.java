package com.chatcrmlite.backend.services;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.atomic.DoubleAdder;

@Service
public class CostTracker {
    private static final Logger log = LoggerFactory.getLogger(CostTracker.class);

    private static final double COST_PER_1M_INPUT_TOKENS = 0.075;
    private static final double COST_PER_1M_OUTPUT_TOKENS = 0.30;

    private final DoubleAdder totalEstimatedCost;

    public CostTracker(MeterRegistry meterRegistry) {
        this.totalEstimatedCost = new DoubleAdder();
        meterRegistry.gauge("ai.cost.estimated.total", this.totalEstimatedCost, DoubleAdder::sum);
    }

    public double trackCost(int inputTokens, int outputTokens, UUID tenantId) {
        double inputCost = (inputTokens / 1_000_000.0) * COST_PER_1M_INPUT_TOKENS;
        double outputCost = (outputTokens / 1_000_000.0) * COST_PER_1M_OUTPUT_TOKENS;
        double callCost = inputCost + outputCost;

        totalEstimatedCost.add(callCost);

        log.debug("[CostTracker] Tenant: {} | Input: {} | Output: {} | Cost: ${}", 
                tenantId, inputTokens, outputTokens, String.format("%.6f", callCost));

        return callCost;
    }
}
