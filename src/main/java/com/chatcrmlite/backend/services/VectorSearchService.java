package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.repositories.DocumentChunkRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
public class VectorSearchService {
    private static final Logger log = LoggerFactory.getLogger(VectorSearchService.class);

    private final DocumentChunkRepository repository;
    private final MeterRegistry meterRegistry;

    public VectorSearchService(DocumentChunkRepository repository, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.meterRegistry = meterRegistry;
    }

    public List<String> search(UUID tenantId, float[] queryEmbedding, int topK) {
        long start = System.currentTimeMillis();
        try {
            String embeddingString = Arrays.toString(queryEmbedding);
            
            Timer.Sample sample = Timer.start(meterRegistry);
            List<String> results = repository.findSimilarChunks(tenantId, embeddingString, topK);
            sample.stop(meterRegistry.timer("vector.search.latency", "tenant", tenantId.toString()));
            
            long latency = System.currentTimeMillis() - start;
            log.info("[VectorSearch] Tenant: {} | Latency: {}ms | Results: {}", tenantId, latency, results.size());
            
            return results;
        } catch (Exception e) {
            log.error("Vector search failed for tenant {}: {}", tenantId, e.getMessage());
            return List.of();
        }
    }
}
