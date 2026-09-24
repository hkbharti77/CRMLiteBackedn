package com.chatcrmlite.backend.services.ai;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.output.Response;

import java.time.Duration;
import java.util.List;

public class TestBedrockDirect {
    public static void main(String[] args) {
        String apiKey = System.getenv("AWS_BEDROCK_API_KEY");
        String modelName = "us.amazon.nova-micro-v1:0";
        String baseUrl = "https://bedrock-runtime.us-east-1.amazonaws.com";

        BedrockChatModel model = BedrockChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .baseUrl(baseUrl)
                .timeout(Duration.ofSeconds(15))
                .build();

        System.out.println("--- Testing BedrockChatModel ---");
        Response<AiMessage> response = model.generate(List.of(UserMessage.from("Hello Bedrock")));
        System.out.println("RESULT TEXT: " + response.content().text());
        if (response.tokenUsage() != null) {
            System.out.println("TOKENS: " + response.tokenUsage());
        } else {
            System.out.println("TOKENS: null (fallback response returned)");
        }
    }
}
