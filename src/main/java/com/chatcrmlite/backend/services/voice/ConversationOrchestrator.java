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
        
        List<ChatMessage> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(SystemMessage.from(systemPrompt));
        }
        if (previousMessages != null) {
            messages.addAll(previousMessages);
        }
        messages.add(UserMessage.from(userTranscript));

        // ── Dynamic specs from FlowConfigService (same as WhatsApp/chat bots) ──
        List<ToolSpecification> tools = toolRegistry.getEnabledToolSpecsForTenant(context.tenantId());
        log.debug("[Orchestrator] Turn for tenant={} with {} dynamic tools", context.tenantId(), tools.size());

        int maxHops = 3;
        int currentHop = 0;
        StringBuilder finalResponse = new StringBuilder();

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
                return "I'm sorry, I'm having trouble processing that right now.";
            }

            // If there's text content, append it
            if (response.getContent() != null && !response.getContent().isBlank()) {
                finalResponse.append(response.getContent()).append(" ");
            }

            // Check if tools were called
            if (response.getToolExecutionRequests() != null && !response.getToolExecutionRequests().isEmpty()) {
                // For Langchain4j proper history, we must add the exact AiMessage with ToolExecutionRequests
                AiMessage aiMessage = AiMessage.from(response.getToolExecutionRequests());
                messages.add(aiMessage);

                for (ToolExecutionRequest toolReq : response.getToolExecutionRequests()) {
                    log.info("Executing tool: {}", toolReq.name());
                    ToolExecutionResult toolResult = toolRouter.execute(toolReq, context);
                    
                    // Add ToolExecutionResultMessage to history
                    String resultString = String.format("Status: %s\nResult: %s\nErrorCode: %s", 
                            toolResult.status(), toolResult.result(), toolResult.errorCode());
                    
                    messages.add(ToolExecutionResultMessage.from(toolReq.id(), toolReq.name(), resultString));
                }
                
                // Hop again to let LLM read the tool result and respond
                currentHop++;
            } else {
                // No more tool calls, we are done
                break;
            }
        }

        if (finalResponse.length() == 0) {
            return "I'm sorry, an error occurred while processing your request.";
        }

        return finalResponse.toString().trim();
    }
}
