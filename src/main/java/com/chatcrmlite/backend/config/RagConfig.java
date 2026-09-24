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

import com.chatcrmlite.backend.services.ai.AiOrchestrator;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Conditional;
import org.springframework.beans.factory.ObjectProvider;

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

    @Value("${ai.openrouter.api-key:}")
    private String openRouterApiKey;

    @Value("${ai.openrouter.base-url:https://openrouter.ai/api/v1}")
    private String openRouterBaseUrl;

    @Value("${ai.openrouter.model-name:google/gemini-2.5-flash}")
    private String openRouterModelName;

    @Value("${ai.bedrock.api-key:}")
    private String bedrockApiKey;

    @Value("${ai.bedrock.model-name:us.amazon.nova-micro-v1:0}")
    private String bedrockModelName;

    @Value("${ai.bedrock.base-url:https://bedrock-runtime.us-east-1.amazonaws.com}")
    private String bedrockBaseUrl;

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
     * Configuration of Chat Model bean supporting Gemini, Bedrock, OpenRouter, and Ollama/Local OpenAI
     */
    @Bean
    public ChatLanguageModel geminiChatModel() {
        if ("bedrock".equalsIgnoreCase(aiProvider)) {
            if (bedrockApiKey == null || bedrockApiKey.isBlank()) {
                return null;
            }
            String url = (bedrockBaseUrl != null && !bedrockBaseUrl.isBlank()) 
                    ? bedrockBaseUrl.trim() 
                    : "https://bedrock-runtime.us-east-1.amazonaws.com";
            return com.chatcrmlite.backend.services.ai.BedrockChatModel.builder()
                    .baseUrl(url)
                    .apiKey(bedrockApiKey)
                    .modelName(bedrockModelName)
                    .timeout(java.time.Duration.ofSeconds(120))
                    .build();
        }

        if ("openrouter".equalsIgnoreCase(aiProvider)) {
            if (openRouterApiKey == null || openRouterApiKey.isBlank()) {
                return null;
            }
            String url = (openRouterBaseUrl != null && !openRouterBaseUrl.isBlank()) 
                    ? openRouterBaseUrl.trim() 
                    : "https://openrouter.ai/api/v1";
            return OpenAiChatModel.builder()
                    .baseUrl(url)
                    .apiKey(openRouterApiKey)
                    .modelName(openRouterModelName)
                    .timeout(java.time.Duration.ofSeconds(120))
                    .maxRetries(1)
                    .build();
        }

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
    @Conditional(AiEnabledCondition.class)
    public AiProvider chatLanguageModelAiProvider(
            @org.springframework.context.annotation.Lazy ChatLanguageModel chatLanguageModel,
            ModelHealthMonitor healthMonitor) {
        return new ChatLanguageModelAiProvider(
                chatLanguageModel, 
                healthMonitor, 
                aiProvider, 
                modelName, 
                openAiModelName,
                openRouterModelName,
                bedrockModelName
        );
    }

    @Bean
    @Conditional(AiEnabledCondition.class)
    public AiOrchestrator aiOrchestrator(
            ObjectProvider<AiProvider> providerSource,
            ModelHealthMonitor healthMonitor) {
        
        List<AiProvider> providers = providerSource.orderedStream().collect(Collectors.toList());
        org.slf4j.LoggerFactory.getLogger(RagConfig.class)
            .info("[AI-Config] AiOrchestrator initialized with {} provider(s)", providers.size());
        return new AiOrchestrator(providers, healthMonitor);
    }

    public static class ChatLanguageModelAiProvider implements AiProvider {
        private final ChatLanguageModel chatLanguageModel;
        private final ModelHealthMonitor healthMonitor;
        private final String aiProvider;
        private final String modelName;
        private final String openAiModelName;
        private final String openRouterModelName;
        private final String bedrockModelName;

        public ChatLanguageModelAiProvider(ChatLanguageModel chatLanguageModel,
                                           ModelHealthMonitor healthMonitor,
                                           String aiProvider,
                                           String modelName,
                                           String openAiModelName,
                                           String openRouterModelName,
                                           String bedrockModelName) {
            this.chatLanguageModel = chatLanguageModel;
            this.healthMonitor = healthMonitor;
            this.aiProvider = aiProvider;
            this.modelName = modelName;
            this.openAiModelName = openAiModelName;
            this.openRouterModelName = openRouterModelName;
            this.bedrockModelName = bedrockModelName;
        }

        @Override
        public AiResponse generate(AiRequest request) {
            if (chatLanguageModel == null) {
                throw new IllegalStateException("No active ChatLanguageModel bean configured!");
            }
            long start = System.currentTimeMillis();
            
            List<dev.langchain4j.data.message.ChatMessage> messages = request.getMessages();
            if (messages == null || messages.isEmpty()) {
                messages = new java.util.ArrayList<>();
                if (request.getSystemInstruction() != null && !request.getSystemInstruction().isBlank()) {
                    messages.add(SystemMessage.from(request.getSystemInstruction().trim()));
                }
                String safePrompt = (request.getPrompt() != null && !request.getPrompt().isBlank())
                        ? request.getPrompt().trim()
                        : "Hello";
                messages.add(UserMessage.from(safePrompt));
            }

            Response<AiMessage> response = null;
            try {
                if (request.getTools() != null && !request.getTools().isEmpty()) {
                    response = chatLanguageModel.generate(messages, request.getTools());
                } else {
                    response = chatLanguageModel.generate(messages);
                }
            } catch (IllegalArgumentException e) {
                org.slf4j.LoggerFactory.getLogger(ChatLanguageModelAiProvider.class)
                        .warn("[AiProvider] LangChain4j validation error ({}). Retrying without tools...", e.getMessage());
                try {
                    response = chatLanguageModel.generate(messages);
                } catch (Exception ex) {
                    org.slf4j.LoggerFactory.getLogger(ChatLanguageModelAiProvider.class)
                            .error("[AiProvider] Fallback generation failed: {}", ex.getMessage());
                    return AiResponse.builder()
                            .content("Thank you! I have processed your request.")
                            .tokensUsed(0)
                            .latencyMs(System.currentTimeMillis() - start)
                            .provider(aiProvider)
                            .build();
                }
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(ChatLanguageModelAiProvider.class)
                        .error("[AiProvider] Generation exception: {}", e.getMessage(), e);
                throw e;
            }
            
            long duration = System.currentTimeMillis() - start;
            int tokens = (response != null && response.tokenUsage() != null) ? response.tokenUsage().totalTokenCount() : 0;
            
            AiMessage aiMessage = (response != null) ? response.content() : null;
            String textContent = (aiMessage != null && aiMessage.text() != null) ? aiMessage.text() : "";
            
            return AiResponse.builder()
                    .content(textContent)
                    .toolExecutionRequests(aiMessage != null ? aiMessage.toolExecutionRequests() : null)
                    .tokensUsed(tokens)
                    .latencyMs(duration)
                    .provider(aiProvider)
                    .build();
        }

        @Override
        public String getModelName() {
            if ("bedrock".equalsIgnoreCase(aiProvider)) {
                return bedrockModelName;
            }
            if ("openrouter".equalsIgnoreCase(aiProvider)) {
                return openRouterModelName;
            }
            if ("openai".equalsIgnoreCase(aiProvider) || "ollama".equalsIgnoreCase(aiProvider) || "local".equalsIgnoreCase(aiProvider)) {
                return openAiModelName;
            }
            return modelName;
        }

        @Override
        public boolean isHealthy() {
            return chatLanguageModel != null && (healthMonitor == null || !healthMonitor.isCircuitOpen(getModelName()));
        }

        @Override
        public double getCostPer1kTokens() {
            if ("openrouter".equalsIgnoreCase(aiProvider)) {
                return 0.0002;
            }
            return "google".equalsIgnoreCase(aiProvider) || "gemini".equalsIgnoreCase(aiProvider) ? 0.00015 : 0.0;
        }
    }
}
