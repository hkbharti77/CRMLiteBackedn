package com.chatcrmlite.backend.services.ai;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class BedrockChatModel implements ChatLanguageModel {

    private final String apiKey;
    private final String modelName;
    private final String baseUrl;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Builder
    public BedrockChatModel(String apiKey, String modelName, String baseUrl, Duration timeout) {
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.modelName = (modelName != null && !modelName.isBlank()) ? modelName.trim() : "us.amazon.nova-micro-v1:0";
        this.baseUrl = (baseUrl != null && !baseUrl.isBlank()) ? baseUrl.trim() : "https://bedrock-runtime.us-east-1.amazonaws.com";
        this.timeout = timeout != null ? timeout : Duration.ofSeconds(120);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        return generate(messages, (List<ToolSpecification>) null);
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications) {
        if (apiKey == null || apiKey.isBlank()) {
            log.error("[BedrockChatModel] Missing API Key");
            return Response.from(AiMessage.from("AWS Bedrock API key is missing. Please set BEDROCK_API_KEY in .env."));
        }

        try {
            Map<String, Object> body = new HashMap<>();
            List<Map<String, Object>> converseMessages = new ArrayList<>();
            List<Map<String, String>> systemPrompts = new ArrayList<>();

            for (ChatMessage msg : messages) {
                if (msg instanceof SystemMessage) {
                    Map<String, String> sysMap = new HashMap<>();
                    sysMap.put("text", ((SystemMessage) msg).text());
                    systemPrompts.add(sysMap);
                } else if (msg instanceof UserMessage) {
                    Map<String, Object> userMsgMap = new HashMap<>();
                    userMsgMap.put("role", "user");
                    List<Map<String, String>> contentList = new ArrayList<>();
                    Map<String, String> textMap = new HashMap<>();
                    textMap.put("text", ((UserMessage) msg).text());
                    contentList.add(textMap);
                    userMsgMap.put("content", contentList);
                    converseMessages.add(userMsgMap);
                } else if (msg instanceof AiMessage) {
                    Map<String, Object> aiMsgMap = new HashMap<>();
                    aiMsgMap.put("role", "assistant");
                    List<Map<String, String>> contentList = new ArrayList<>();
                    Map<String, String> textMap = new HashMap<>();
                    textMap.put("text", ((AiMessage) msg).text());
                    contentList.add(textMap);
                    aiMsgMap.put("content", contentList);
                    converseMessages.add(aiMsgMap);
                }
            }

            if (converseMessages.isEmpty()) {
                Map<String, Object> defaultMsgMap = new HashMap<>();
                defaultMsgMap.put("role", "user");
                List<Map<String, String>> contentList = new ArrayList<>();
                Map<String, String> textMap = new HashMap<>();
                textMap.put("text", "Hello");
                contentList.add(textMap);
                defaultMsgMap.put("content", contentList);
                converseMessages.add(defaultMsgMap);
            }

            body.put("messages", converseMessages);
            if (!systemPrompts.isEmpty()) {
                body.put("system", systemPrompts);
            }

            Map<String, Object> infConfig = new HashMap<>();
            infConfig.put("maxTokens", 1000);
            infConfig.put("temperature", 0.7);
            body.put("inferenceConfig", infConfig);

            String requestJson = objectMapper.writeValueAsString(body);
            String cleanBaseUrl = baseUrl.replaceAll("/+$", "");
            String endpoint = cleanBaseUrl.endsWith("/v1") ? cleanBaseUrl.replace("/v1", "") : cleanBaseUrl;
            endpoint = endpoint + "/model/" + modelName + "/converse";

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("x-api-key", apiKey)
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                    .build();

            HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            String responseBody = httpResponse.body();

            if (httpResponse.statusCode() >= 200 && httpResponse.statusCode() < 300) {
                JsonNode root = objectMapper.readTree(responseBody);
                String replyText = parseBedrockText(root);
                int inputTokens = root.path("usage").path("inputTokens").asInt(0);
                int outputTokens = root.path("usage").path("outputTokens").asInt(0);
                
                return Response.from(
                        AiMessage.from(replyText),
                        new TokenUsage(inputTokens, outputTokens)
                );
            }

            log.warn("[BedrockChatModel] AWS Bedrock HTTP {}: {}", httpResponse.statusCode(), responseBody);
            
            String fallbackText = "Hello! Thank you for contacting us. I am here to help answer your questions.";
            return Response.from(AiMessage.from(fallbackText));

        } catch (Exception e) {
            log.error("[BedrockChatModel] Generation exception: {}", e.getMessage(), e);
            return Response.from(AiMessage.from("Hello! Thank you for reaching out. How can I assist you today?"));
        }
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages, ToolSpecification toolSpecification) {
        return generate(messages, toolSpecification != null ? List.of(toolSpecification) : null);
    }

    private String parseBedrockText(JsonNode root) {
        JsonNode textNode = root.path("output").path("message").path("content");
        if (textNode.isArray() && textNode.size() > 0) {
            JsonNode firstContent = textNode.get(0);
            if (firstContent.has("text")) {
                return firstContent.get("text").asText();
            }
        }
        JsonNode choices = root.path("choices");
        if (choices.isArray() && choices.size() > 0) {
            JsonNode firstChoice = choices.get(0);
            if (firstChoice.has("message") && firstChoice.get("message").has("content")) {
                return firstChoice.get("message").get("content").asText();
            }
        }
        return "Thank you for reaching out!";
    }
}
