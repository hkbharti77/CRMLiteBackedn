package com.chatcrmlite.backend.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2q.AllMiniLmL6V2QuantizedEmbeddingModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.chatcrmlite.backend.services.ai.AiProvider;
import com.chatcrmlite.backend.services.ai.AiRequest;
import com.chatcrmlite.backend.services.ai.AiResponse;
import com.chatcrmlite.backend.services.ai.ModelHealthMonitor;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.output.Response;
import java.util.List;

@Configuration
public class RagConfig {

    @Value("${ai.provider:google}")
    private String aiProvider;

    @Value("${langchain4j.google-ai.gemini.api-key:}")
    private String geminiApiKey;

    @Value("${langchain4j.google-ai.gemini.model-name:gemini-1.5-flash}")
    private String modelName;

    @Value("${ai.openai.base-url:}")
    private String openAiBaseUrl;

    @Value("${ai.openai.api-key:ollama}")
    private String openAiApiKey;

    @Value("${ai.openai.model-name:gemma-3-1b-it-Q4_K_M}")
    private String openAiModelName;

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
     * Configuration of Chat Model bean supporting Gemini or Ollama (OpenAI compatible)
     */
    @Bean
    public ChatLanguageModel geminiChatModel() {
        if ("openai".equalsIgnoreCase(aiProvider) || "ollama".equalsIgnoreCase(aiProvider) || "local".equalsIgnoreCase(aiProvider)) {
            if (openAiBaseUrl == null || openAiBaseUrl.isBlank()) {
                return null;
            }
            String cleanUrl = openAiBaseUrl.trim().replace(" ", "");
            return OpenAiChatModel.builder()
                    .baseUrl(cleanUrl)
                    .apiKey(openAiApiKey)
                    .modelName(openAiModelName)
                    .timeout(java.time.Duration.ofSeconds(300))
                    .maxRetries(1)
                    .build();
        }

        if (geminiApiKey == null || geminiApiKey.isBlank()) {
            return null; // Handle missing key gracefully in RagRetrievalService
        }

        return GoogleAiGeminiChatModel.builder()
                .apiKey(geminiApiKey)
                .modelName(modelName)
                .maxRetries(1)
                .build();
    }

    @Bean
    public AiProvider chatLanguageModelAiProvider(
            @org.springframework.context.annotation.Lazy ChatLanguageModel chatLanguageModel,
            ModelHealthMonitor healthMonitor) {
        return new AiProvider() {
            @Override
            public AiResponse generate(AiRequest request) {
                if (chatLanguageModel == null) {
                    throw new IllegalStateException("No active ChatLanguageModel bean configured!");
                }
                long start = System.currentTimeMillis();
                List<dev.langchain4j.data.message.ChatMessage> messages = new java.util.ArrayList<>();
                if (request.getSystemInstruction() != null && !request.getSystemInstruction().isBlank()) {
                    messages.add(SystemMessage.from(request.getSystemInstruction()));
                }
                messages.add(UserMessage.from(request.getPrompt()));

                Response<AiMessage> response = chatLanguageModel.generate(messages);
                long duration = System.currentTimeMillis() - start;

                int tokens = response.tokenUsage() != null ? response.tokenUsage().totalTokenCount() : 0;

                return AiResponse.builder()
                        .content(response.content().text())
                        .tokensUsed(tokens)
                        .latencyMs(duration)
                        .provider(aiProvider)
                        .build();
            }

            @Override
            public String getModelName() {
                return "google".equalsIgnoreCase(aiProvider) ? modelName : openAiModelName;
            }

            @Override
            public boolean isHealthy() {
                return chatLanguageModel != null && !healthMonitor.isCircuitOpen(getModelName());
            }

            @Override
            public double getCostPer1kTokens() {
                return "google".equalsIgnoreCase(aiProvider) ? 0.00015 : 0.0;
            }
        };
    }
}
