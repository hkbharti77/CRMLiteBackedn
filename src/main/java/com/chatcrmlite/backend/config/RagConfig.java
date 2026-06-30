package com.chatcrmlite.backend.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.googleai.GoogleAiEmbeddingModel;
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
     * Gemini API-based embedding model — zero local RAM cost.
     * Replaces the local ONNX model which consumed 150MB+ of native memory
     * and crashed the 512MB Render Free Tier server.
     * Produces 768-dimensional vectors (text-embedding-004).
     */
    @Bean
    @org.springframework.context.annotation.Profile("!test")
    public EmbeddingModel embeddingModel() {
        return GoogleAiEmbeddingModel.builder()
                .apiKey(geminiApiKey)
                .modelName("text-embedding-004")
                .build();
    }

    @Bean
    @org.springframework.context.annotation.Profile("test")
    public EmbeddingModel testEmbeddingModel() {
        return new EmbeddingModel() {
            @Override
            public dev.langchain4j.model.output.Response<dev.langchain4j.data.embedding.Embedding> embed(dev.langchain4j.data.segment.TextSegment textSegment) {
                float[] vector = new float[768]; // matches Gemini text-embedding-004 dimensions
                return dev.langchain4j.model.output.Response.from(dev.langchain4j.data.embedding.Embedding.from(vector));
            }

            @Override
            public dev.langchain4j.model.output.Response<java.util.List<dev.langchain4j.data.embedding.Embedding>> embedAll(java.util.List<dev.langchain4j.data.segment.TextSegment> textSegments) {
                java.util.List<dev.langchain4j.data.embedding.Embedding> embeddings = new java.util.ArrayList<>();
                for (dev.langchain4j.data.segment.TextSegment segment : textSegments) {
                    embeddings.add(dev.langchain4j.data.embedding.Embedding.from(new float[768]));
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
