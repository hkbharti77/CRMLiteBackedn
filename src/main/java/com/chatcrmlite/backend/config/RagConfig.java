package com.chatcrmlite.backend.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2q.AllMiniLmL6V2QuantizedEmbeddingModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RagConfig {

    @Value("${langchain4j.google-ai.gemini.api-key:}")
    private String geminiApiKey;

    @Value("${langchain4j.google-ai.gemini.model-name:gemini-1.5-flash}")
    private String modelName;

    /**
     * Local ONNX embedding model — no API key required.
     * Produces 384-dimensional vectors compatible with the document_chunks schema.
     */
    @Bean
    @org.springframework.context.annotation.Profile("!test")
    public EmbeddingModel embeddingModel() {
        return new AllMiniLmL6V2QuantizedEmbeddingModel();
    }

    @Bean
    @org.springframework.context.annotation.Profile("test")
    public EmbeddingModel testEmbeddingModel() {
        return new EmbeddingModel() {
            @Override
            public dev.langchain4j.model.output.Response<dev.langchain4j.data.embedding.Embedding> embed(dev.langchain4j.data.segment.TextSegment textSegment) {
                float[] vector = new float[384];
                return dev.langchain4j.model.output.Response.from(dev.langchain4j.data.embedding.Embedding.from(vector));
            }

            @Override
            public dev.langchain4j.model.output.Response<java.util.List<dev.langchain4j.data.embedding.Embedding>> embedAll(java.util.List<dev.langchain4j.data.segment.TextSegment> textSegments) {
                java.util.List<dev.langchain4j.data.embedding.Embedding> embeddings = new java.util.ArrayList<>();
                for (dev.langchain4j.data.segment.TextSegment segment : textSegments) {
                    embeddings.add(dev.langchain4j.data.embedding.Embedding.from(new float[384]));
                }
                return dev.langchain4j.model.output.Response.from(embeddings);
            }
        };
    }

    /**
     * Manual configuration of Gemini Chat Model bean since
     * the Spring Boot starter is not available in LangChain4j 0.35.0.
     */
    @Bean
    public ChatLanguageModel geminiChatModel() {
        if (geminiApiKey == null || geminiApiKey.isBlank()) {
            return null; // Handle missing key gracefully in RagRetrievalService
        }

        return GoogleAiGeminiChatModel.builder()
                .apiKey(geminiApiKey)
                .modelName(modelName)
                .maxRetries(3)
                .build();
    }
}
