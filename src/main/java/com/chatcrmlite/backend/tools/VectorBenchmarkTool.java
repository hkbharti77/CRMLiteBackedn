package com.chatcrmlite.backend.tools;

import com.chatcrmlite.backend.services.SemanticRetriever;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.List;

@Slf4j
@Component
@Profile("benchmark")
public class VectorBenchmarkTool implements CommandLineRunner {

    private final SemanticRetriever semanticRetriever;

    public VectorBenchmarkTool(SemanticRetriever semanticRetriever) {
        this.semanticRetriever = semanticRetriever;
    }

    @Override
    public void run(String... args) throws Exception {
        log.info("Starting Vector Benchmark...");
        
        // Note: For a real benchmark, provide a valid UUID that has substantial document chunks indexed
        UUID testTenantId = UUID.randomUUID(); 

        int iterations = 100;
        long totalLatency = 0;

        log.info("Executing {} vector search queries...", iterations);

        for (int i = 0; i < iterations; i++) {
            long start = System.currentTimeMillis();
            List<String> results = semanticRetriever.retrieveRelevantContext("benchmark query test " + i, testTenantId, 5);
            long latency = System.currentTimeMillis() - start;
            totalLatency += latency;

            if (i % 20 == 0) {
                log.info("Iteration {}: {}ms", i, latency);
            }
        }

        log.info("Benchmark complete. Avg vector retrieval latency: {}ms per query", (double) totalLatency / iterations);
    }
}
