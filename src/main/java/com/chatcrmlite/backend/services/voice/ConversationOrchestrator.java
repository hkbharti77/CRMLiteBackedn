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

    private final AiOrchestrator aiOrchestrator;
    private final ToolRegistry toolRegistry;
    private final ToolRouter toolRouter;

    public ConversationOrchestrator(AiOrchestrator aiOrchestrator, ToolRegistry toolRegistry, ToolRouter toolRouter) {
        this.aiOrchestrator = aiOrchestrator;
        this.toolRegistry = toolRegistry;
        this.toolRouter = toolRouter;
    }

    /**
     * Executes a conversational turn with tool calling support.
     * Tool specifications are loaded DYNAMICALLY from FlowConfigService —
     * the same source used by the WhatsApp and chat bots.
     */
    public String executeTurn(String systemPrompt, String userTranscript, List<ChatMessage> previousMessages, ToolExecutionContext context) {
        
        // ── Dynamic specs from FlowConfigService (same as WhatsApp/chat bots) ──
        List<ToolSpecification> tools = toolRegistry.getEnabledToolSpecsForTenant(context.tenantId());
        log.debug("[Orchestrator] Turn for tenant={} with {} dynamic tools", context.tenantId(), tools.size());

        StringBuilder fullSystemPrompt = new StringBuilder();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            fullSystemPrompt.append(systemPrompt.trim()).append("\n\n");
        }

        if (tools != null && !tools.isEmpty()) {
            fullSystemPrompt.append("--- INSTRUCTIONS FOR DYNAMIC VOICE FORMS & INTENT ROUTING ---\n")
                    .append("1. INTENT MATCHING: Identify if the user wants to enquire/leave details (lead), book an appointment, make a reservation, or file a support ticket.\n")
                    .append("2. CONVERSATIONAL SLOT FILLING: Look at the required parameters for the corresponding tool specification.\n")
                    .append("   - If any required field is missing in conversation memory, ask the caller for it conversationally (ask 1-2 questions at a time).\n")
                    .append("   - Keep asking naturally until all required parameters are collected.\n")
                    .append("3. AUTOMATIC TOOL EXECUTION: Once all required fields are collected, call the matching tool immediately and summarize the outcome to the caller verbally.\n")
                    .append("4. IMPORTANT VOICE RULE: Never say or read out lead numbers, ticket IDs, or internal reference numbers to the caller. Simply tell them that their enquiry, demo request, or ticket has been submitted successfully.\n");
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
