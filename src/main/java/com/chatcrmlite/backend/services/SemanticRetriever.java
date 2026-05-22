package com.chatcrmlite.backend.services;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2q.AllMiniLmL6V2QuantizedEmbeddingModel;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@lombok.RequiredArgsConstructor
@Service
@CircuitBreaker(name = "ragRetriever")
public class SemanticRetriever {
    private static final Logger log = LoggerFactory.getLogger(SemanticRetriever.class);

    private final VectorSearchService vectorSearchService;
    private final MeterRegistry meterRegistry;
    private final EmbeddingModel embeddingModel;

    public List<String> retrieveRelevantContext(String query, UUID tenantId, int maxResults) {
        dev.langchain4j.data.embedding.Embedding embedding = embeddingModel.embed(query).content();
        return retrieveWithEmbedding(embedding, tenantId, maxResults);
    }

    public List<String> retrieveWithEmbedding(dev.langchain4j.data.embedding.Embedding queryEmbedding, UUID tenantId, int maxResults) {
        Timer.Sample sample = Timer.start(meterRegistry);

        float[] vector = queryEmbedding.vector();
        int fetchCount = maxResults * 2; 
        List<String> chunks = vectorSearchService.search(tenantId, vector, fetchCount);

        List<String> reranked = chunks.stream()
            .limit(maxResults)
            .toList();

        sample.stop(meterRegistry.timer("vector.retrieval.latency", "tenant", tenantId.toString()));
        meterRegistry.counter("vector.retrieval.queries", "tenant", tenantId.toString()).increment();

        log.info("[SemanticRetriever] Query resolved for tenant: {}. Retrieved {} context chunks.", tenantId, reranked.size());
        
        return reranked;
    }
}
