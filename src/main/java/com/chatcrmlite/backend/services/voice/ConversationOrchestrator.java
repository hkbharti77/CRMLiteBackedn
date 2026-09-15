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
                    .append("GENERAL RULE: Your primary job is to ANSWER the user's question naturally and helpfully. Do NOT ask for personal details (name, email, etc.) unless the user has clearly and explicitly expressed one of the specific intents below.\n\n")
                    .append("TOOL TRIGGER — only activate a tool flow when the user EXPLICITLY:\n")
                    .append("  - Wants to leave their details / request a callback / submit an enquiry → use create_lead\n")
                    .append("  - Wants to book an appointment → use book_appointment\n")
                    .append("  - Wants to make a reservation or booking → use create_booking\n")
                    .append("  - Has a problem, complaint, or needs support → use submit_support_ticket\n\n")
                    .append("WHEN a tool IS triggered:\n")
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
            if (response.getContent() != null && !response.getContent().isBlank()) {
                finalResponse.append(response.getContent().trim()).append(" ");
            }

            // Check if tools were called
            if (response.getToolExecutionRequests() != null && !response.getToolExecutionRequests().isEmpty()) {
                toolExecuted = true;

                // Add exact AiMessage using direct constructor to avoid LangChain4j null/blank text exception
                AiMessage aiMessage;
                String responseContent = (response.getContent() != null && !response.getContent().isBlank()) ? response.getContent().trim() : null;
                if (responseContent != null) {
                    aiMessage = new AiMessage(responseContent, response.getToolExecutionRequests());
                } else {
                    aiMessage = new AiMessage(response.getToolExecutionRequests());
                }
                messages.add(aiMessage);

                for (ToolExecutionRequest toolReq : response.getToolExecutionRequests()) {
                    log.info("Executing tool: {}", toolReq.name());
                    ToolExecutionResult toolResult = toolRouter.execute(toolReq, context);
                    
                    // Add ToolExecutionResultMessage to history
                    String resultString = String.format("Status: %s\nResult: %s\nErrorCode: %s", 
                            toolResult.status(), toolResult.result(), toolResult.errorCode());
                    
                    String safeId = (toolReq.id() != null && !toolReq.id().isBlank()) ? toolReq.id().trim() : "call_" + java.util.UUID.randomUUID().toString().substring(0, 8);
                    String safeName = (toolReq.name() != null && !toolReq.name().isBlank()) ? toolReq.name().trim() : "tool";
                    String safeResult = (!resultString.isBlank()) ? resultString.trim() : "Success";

                    messages.add(ToolExecutionResultMessage.from(safeId, safeName, safeResult));
                }
                
                // Hop again to let LLM read the tool result and respond
                currentHop++;
            } else {
                // No more tool calls, we are done
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
