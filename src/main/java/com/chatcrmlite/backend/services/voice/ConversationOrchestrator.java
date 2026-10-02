package com.chatcrmlite.backend.services.voice;

import com.chatcrmlite.backend.services.ai.AiOrchestrator;
import com.chatcrmlite.backend.services.ai.AiRequest;
import com.chatcrmlite.backend.services.ai.AiResponse;
import com.chatcrmlite.backend.services.voice.tools.ToolExecutionContext;
import com.chatcrmlite.backend.services.voice.tools.ToolExecutionResult;
import com.chatcrmlite.backend.services.voice.tools.ToolRegistry;
import com.chatcrmlite.backend.services.voice.tools.ToolRouter;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class ConversationOrchestrator {

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AiOrchestrator aiOrchestrator;
    
    private final ToolRegistry toolRegistry;
    private final ToolRouter toolRouter;

    public ConversationOrchestrator(ToolRegistry toolRegistry, ToolRouter toolRouter) {
        this.toolRegistry = toolRegistry;
        this.toolRouter = toolRouter;
    }

    /**
     * Executes a conversational turn with tool calling support.
     * Tool specifications are loaded DYNAMICALLY from FlowConfigService —
     * the same source used by the WhatsApp and chat bots.
     */
    public String executeTurn(String systemPrompt, String userTranscript, List<ChatMessage> previousMessages, ToolExecutionContext context) {
        if (aiOrchestrator == null) {
            log.warn("[ConversationOrchestrator] AI unavailable: AiOrchestrator is not configured. Returning fallback.");
            return "I'm sorry, the AI assistant is not available right now. Please try again later.";
        }
        
        // ── Dynamic specs from FlowConfigService (same as WhatsApp/chat bots) ──
        List<ToolSpecification> tools = toolRegistry.getEnabledToolSpecsForTenant(context.tenantId());
        log.debug("[Orchestrator] Turn for tenant={} with {} dynamic tools", context.tenantId(), tools.size());

        StringBuilder fullSystemPrompt = new StringBuilder();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            fullSystemPrompt.append(systemPrompt.trim()).append("\n\n");
        }

        if (tools != null && !tools.isEmpty()) {
            fullSystemPrompt.append("--- INSTRUCTIONS FOR TOOL USE & INTENT ROUTING ---\n")
                    .append("GENERAL RULE: Your primary job is to ANSWER the user's question naturally and helpfully. Do NOT ask for personal details (name, email, etc.) unless the user has clearly and explicitly expressed an intent for one of the active tools below.\n\n")
                    .append("ACTIVE TOOLS & INTENT TRIGGERS:\n");

            for (ToolSpecification tool : tools) {
                fullSystemPrompt.append("  - Tool '").append(tool.name()).append("': ").append(tool.description()).append("\n");
            }

            fullSystemPrompt.append("\nWHEN a tool IS triggered:\n")
                    .append("  - Look at that tool's required parameters and collect them conversationally (1-2 fields at a time).\n")
                    .append("  - Once ALL required fields are collected, call the tool immediately.\n")
                    .append("  - Confirm success to the caller in plain language; never read out IDs or reference numbers.\n\n")
                    .append("WHEN no tool intent is detected:\n")
                    .append("  - Simply answer the user's question conversationally. Do not ask for their name, email, or any personal details.\n")
                    .append("  - Keep replies short and voice-friendly.\n");
        }

        List<ChatMessage> messages = new ArrayList<>();
        String trimmedPrompt = fullSystemPrompt.toString().trim();
        if (!trimmedPrompt.isBlank()) {
            messages.add(SystemMessage.from(trimmedPrompt));
        }

        if (previousMessages != null) {
            for (ChatMessage m : previousMessages) {
                if (m != null) {
                    messages.add(m);
                }
            }
        }

        String safeTranscript = (userTranscript != null && !userTranscript.isBlank()) ? userTranscript.trim() : "Hello";
        messages.add(UserMessage.from(safeTranscript));

        int maxHops = 3;
        int currentHop = 0;
        StringBuilder finalResponse = new StringBuilder();
        boolean toolExecuted = false;
        Set<String> executedToolsInThisTurn = new HashSet<>();

        while (currentHop < maxHops) {
            AiRequest request = AiRequest.builder()
                    .messages(new ArrayList<>(messages))
                    .tools(tools)
                    .tenantId(context.tenantId())
                    .complexity(AiRequest.TaskComplexity.LOW)
                    .maxTokens(300)
                    .temperature(0.4)
                    .build();

            AiResponse response = aiOrchestrator.execute(request);

            if (response == null) {
                break;
            }

            // If there's text content, append it
            String rawContent = response.getContent();
            
            // Check for raw XML tool calls hallucinated by model in text content
            List<ToolExecutionRequest> parsedRequests = new ArrayList<>();
            if (rawContent != null && rawContent.contains("<tool_call>")) {
                Pattern pattern = Pattern.compile("<tool_call>(.*?)</tool_call>", Pattern.DOTALL);
                Matcher matcher = pattern.matcher(rawContent);
                while (matcher.find()) {
                    String toolCallBody = matcher.group(1);
                    String toolName = toolCallBody.split("\\r?\\n")[0].trim();
                    // Just a basic parse to avoid crashing, but usually we rely on native tool calls.
                    // For now, we will simply filter this out of the TTS response.
                    log.warn("[Orchestrator] Detected raw XML tool call in text for tool: {}", toolName);
                }
                rawContent = matcher.replaceAll("").trim();
            }

            if (rawContent != null && !rawContent.isBlank()) {
                finalResponse.append(rawContent).append(" ");
            }

            List<ToolExecutionRequest> toolRequests = response.getToolExecutionRequests();
            if (toolRequests == null) {
                toolRequests = new ArrayList<>();
            }

            // Check if tools were called
            if (!toolRequests.isEmpty()) {
                toolExecuted = true;

                AiMessage aiMessage;
                if (rawContent != null && !rawContent.isBlank()) {
                    aiMessage = new AiMessage(rawContent, toolRequests);
                } else {
                    aiMessage = new AiMessage(toolRequests);
                }
                messages.add(aiMessage);

                for (ToolExecutionRequest toolReq : toolRequests) {
                    String toolKey = toolReq.name() + "-" + toolReq.arguments();
                    if (executedToolsInThisTurn.contains(toolKey)) {
                        log.warn("Skipping duplicate tool execution in same turn: {}", toolReq.name());
                        messages.add(ToolExecutionResultMessage.from(toolReq.id(), toolReq.name(), "Duplicate tool execution prevented."));
                        continue;
                    }
                    executedToolsInThisTurn.add(toolKey);
                    
                    log.info("Executing tool: {}", toolReq.name());
                    ToolExecutionResult toolResult = toolRouter.execute(toolReq, context);
                    
                    String resultString = String.format("Status: %s\nResult: %s\nErrorCode: %s", 
                            toolResult.status(), toolResult.result(), toolResult.errorCode());
                    
                    String safeId = (toolReq.id() != null && !toolReq.id().isBlank()) ? toolReq.id().trim() : "call_" + java.util.UUID.randomUUID().toString().substring(0, 8);
                    String safeName = (toolReq.name() != null && !toolReq.name().isBlank()) ? toolReq.name().trim() : "tool";
                    String safeResult = (!resultString.isBlank()) ? resultString.trim() : "Success";

                    messages.add(ToolExecutionResultMessage.from(safeId, safeName, safeResult));
                }
                
                currentHop++;
            } else {
                break;
            }
        }

        if (finalResponse.length() == 0) {
            if (toolExecuted) {
                return "Thank you! I have saved your details successfully. Is there anything else I can help you with?";
            }
            return "Thank you for reaching out! How can I assist you with our services today?";
        }

        return finalResponse.toString().trim();
    }
}
