package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.models.DocumentChunk;
import com.chatcrmlite.backend.repositories.DocumentChunkRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class EmbeddingPersistenceService {
    private static final Logger log = LoggerFactory.getLogger(EmbeddingPersistenceService.class);

    private final DocumentChunkRepository repository;
    private final MeterRegistry meterRegistry;

    private static final long MAX_CHUNKS_PER_TENANT = 10000;

    public EmbeddingPersistenceService(DocumentChunkRepository repository, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public int saveChunks(UUID tenantId, List<DocumentChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return 0;
        }

        long currentCount = repository.countByTenantId(tenantId);
        if (currentCount + chunks.size() > MAX_CHUNKS_PER_TENANT) {
            throw new IllegalStateException("Tenant quota exceeded. Max chunks allowed: " + MAX_CHUNKS_PER_TENANT);
        }

        Timer.Sample sample = Timer.start(meterRegistry);
        int savedCount = 0;
        for (DocumentChunk chunk : chunks) {
            try {
                repository.save(chunk);
                savedCount++;
            } catch (Exception e) {
                log.warn("Duplicate or invalid chunk for tenant {}: {}", tenantId, chunk.getContentHash());
            }
        }
        sample.stop(meterRegistry.timer("vector.ingestion.latency", "tenant", tenantId.toString()));
        meterRegistry.counter("vector.ingestion.count", "tenant", tenantId.toString()).increment(savedCount);
        
        log.info("Persisted {} chunks for tenant {}", savedCount, tenantId);
        return savedCount;
    }
}
