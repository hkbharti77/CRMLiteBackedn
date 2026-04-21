package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.models.DocumentChunk;
import com.chatcrmlite.backend.repositories.DocumentChunkRepository;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2q.AllMiniLmL6V2QuantizedEmbeddingModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RagRetrievalService {

    @Autowired
    private DocumentChunkRepository repository;

    @Autowired
    private LocalVectorStoreService localVectorStore;

    /** 
     * Injected automatically by langchain4j-google-ai-gemini-spring-boot-starter 
     * using properties in application.properties
     */
    @Autowired(required = false)
    private ChatLanguageModel chatLanguageModel;

    private final EmbeddingModel embeddingModel = new AllMiniLmL6V2QuantizedEmbeddingModel();

    /**
     * Hybrid Retrieval + LLM Generation with Circuit Breaker and Token Guard.
     */
    @CircuitBreaker(name = "gemini", fallbackMethod = "fallbackResponse")
    public String getAiResponse(String query, UUID tenantId) {
        // Validation check for the injected model
        if (chatLanguageModel == null) {
            return "AI feature is currently being configured. Please check back later.";
        }

        long start = System.currentTimeMillis();

        // 1. Generate Embedding for the query (Local)
        float[] queryEmbedding = embeddingModel.embed(query).content().vector();

        // 2. In-Memory Vector Search
        List<String> chunks = localVectorStore.search(tenantId, queryEmbedding, 8);
        
        if (chunks.isEmpty()) {
            log.info("[RAG] No context found for tenant {} and query '{}'", tenantId, query);
            return null; 
        }

        // 3. Build Context with Token Guard
        String context = String.join("\n---\n", chunks);

        // 4. Prompting using the injected Spring Bean
        String prompt = "You are a business assistant for a CRM platform.\n\n" +
                        "RULES:\n" +
                        "- Answer ONLY using the provided context below.\n" +
                        "- If the answer is missing from the context, say exactly: \"I don't know\".\n" +
                        "- Do NOT guess or hallucinate.\n" +
                        "- Keep your response short, precise, and professional (under 3 sentences).\n\n" +
                        "CONTEXT:\n" + context + "\n\n" +
                        "QUESTION: " + query;

        String response = chatLanguageModel.generate(prompt);
        
        long llmTime = System.currentTimeMillis() - start;
        log.info("[RAG-LOG] Tenant: {} | Query: {} | Retrieved: {} | Latency: {}ms", tenantId, query, chunks.size(), llmTime);

        if ("I don't know".equalsIgnoreCase(response.trim())) {
            return null;
        }

        return response;
    }

    public String fallbackResponse(String query, UUID tenantId, Throwable t) {
        log.error("[RAG-Fallback] Circuit breaker triggered for query: {}. Error: {}", query, t.getMessage());
        return "I'm having trouble connecting to my knowledge base right now. Please try again later.";
    }
}
