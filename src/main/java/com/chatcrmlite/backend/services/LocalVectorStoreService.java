package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.models.DocumentChunk;
import com.chatcrmlite.backend.repositories.DocumentChunkRepository;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class LocalVectorStoreService {

    @Autowired
    private DocumentChunkRepository repository;

    private final Map<UUID, InMemoryEmbeddingStore<TextSegment>> tenantStores = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        log.info("Initializing Local Vector Stores from DB...");
        try {
            List<DocumentChunk> allChunks = repository.findAll();
            for (DocumentChunk chunk : allChunks) {
                addToMemory(chunk);
            }
            log.info("Loaded {} chunks into memory across {} tenants.", allChunks.size(), tenantStores.size());
        } catch (Exception e) {
            log.error("Failed to load chunks from DB on startup. DB might be empty or schema not ready: {}", e.getMessage());
        }
    }

    public void addToMemory(DocumentChunk chunk) {
        tenantStores.computeIfAbsent(chunk.getTenantId(), k -> new InMemoryEmbeddingStore<>())
                .add(new Embedding(chunk.getEmbedding()), TextSegment.from(chunk.getContent(), Metadata.from("docId", chunk.getDocumentId().toString())));
    }

    public void removeDocumentMemory(UUID documentId, UUID tenantId) {
        // Since InMemoryEmbeddingStore does not support direct deletion easily, we rebuild the tenant index
        rebuildTenantStore(tenantId);
    }
    
    public void rebuildTenantStore(UUID tenantId) {
        List<DocumentChunk> chunks = repository.findByTenantId(tenantId);
        InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
        for (DocumentChunk chunk : chunks) {
            store.add(new Embedding(chunk.getEmbedding()), TextSegment.from(chunk.getContent(), Metadata.from("docId", chunk.getDocumentId().toString())));
        }
        tenantStores.put(tenantId, store);
    }

    public List<String> search(UUID tenantId, float[] queryEmbedding, int limit) {
        InMemoryEmbeddingStore<TextSegment> store = tenantStores.get(tenantId);
        if (store == null) return List.of();
        
        List<EmbeddingMatch<TextSegment>> matches = store.findRelevant(new Embedding(queryEmbedding), limit);
        return matches.stream().map(m -> m.embedded().text()).collect(Collectors.toList());
    }
}
