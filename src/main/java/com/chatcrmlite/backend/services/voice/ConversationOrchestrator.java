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
import java.util.Collections;
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
        
        // ── Fast-path check for phatic greetings and conversational acknowledgments ──
        String safeTranscript = (userTranscript != null && !userTranscript.isBlank()) ? userTranscript.trim() : "Hello";
        boolean isSimpleGreeting = isPhaticOrGreeting(safeTranscript);

        // Check if caller's phone is already known from Exotel or WhatsApp Caller ID
        boolean isTelephonyOrWhatsApp = context != null 
                && context.callerPhone() != null 
                && !context.callerPhone().isBlank() 
                && !context.callerPhone().contains("9999999999") 
                && !context.callerPhone().startsWith("web_");
        boolean includePhone = !isTelephonyOrWhatsApp; // Must ask phone on Website Voice Assistant!

        // ── Dynamic specs from FlowConfigService (only attach tools if turn is actionable) ──
        List<ToolSpecification> availableTools = toolRegistry.getEnabledToolSpecsForTenant(context.tenantId(), includePhone);
        List<ToolSpecification> tools = (isSimpleGreeting || availableTools == null) 
                ? Collections.emptyList() 
                : availableTools;

        log.debug("[Orchestrator] Turn for tenant={} isSimpleGreeting={} activeTools={} includePhone={}", 
                context.tenantId(), isSimpleGreeting, tools.size(), includePhone);

        StringBuilder fullSystemPrompt = new StringBuilder();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            fullSystemPrompt.append(systemPrompt.trim()).append("\n\n");
        }

        fullSystemPrompt.append("--- MANDATORY VOICE CALL RULES ---\n")
                .append("1. Keep replies extremely brief (maximum 1 to 2 spoken sentences, under 25 words).\n")
                .append("2. Speak naturally and politely as on a real telephone call.\n")
                .append("3. NEVER use bullet points, markdown symbols, asterisks, or numbered lists.\n")
                .append("4. If the caller is just greeting or saying hello, respond with a short 1-sentence friendly greeting and ask how to help.\n");

        if (includePhone) {
            fullSystemPrompt.append("5. CHANNEL NOTICE (WEBSITE VOICE ASSISTANT): The user is speaking via a browser widget on the website. Their phone number is NOT known yet. You MUST ask the user for their phone number / contact details during the form questions!\n\n");
        } else {
            fullSystemPrompt.append("5. CHANNEL NOTICE (TELEPHONY / WHATSAPP CALL): The user is calling directly from their phone. Their phone number is ALREADY verified and captured via Caller ID. DO NOT ask the caller for their phone number.\n\n");
        }

        if (tools != null && !tools.isEmpty()) {
            fullSystemPrompt.append("--- INSTRUCTIONS FOR DYNAMIC FORM TOOLS & INTENT ROUTING ---\n")
                    .append("You have access to dynamic business tools representing forms configured by the tenant admin in the database.\n\n")
                    .append("INTENT ROUTING GUIDELINES:\n")
                    .append("- If caller wants to book a demo, service, event, reservation, or slot ➔ match and use 'create_booking'.\n")
                    .append("- If caller wants to schedule a meeting/consultation for a specific date and time ➔ match and use 'book_appointment'.\n")
                    .append("- If caller has an enquiry, general question, or asks for a callback ➔ match and use 'create_lead'.\n")
                    .append("- If caller reports a technical issue or complaint ➔ match and use 'submit_support_ticket'.\n\n")
                    .append("CONVERSATIONAL QUESTIONING WORKFLOW (CRITICAL):\n")
                    .append("1. Greet and transition warmly, acknowledging the caller's specific need.\n")
                    .append("2. Ask the form questions ONE QUESTION AT A TIME in conversational spoken language.\n")
                    .append("3. DO NOT execute the tool prematurely! You MUST ask ALL configured questions (both [REQUIRED] and [OPTIONAL] questions like specific requirements, preferred dates/times, or budget) so that complete information is captured.\n")
                    .append("4. If the caller answers multiple fields in a single sentence (e.g. 'I am Rahul and I need a CRM demo in Delhi'), acknowledge both and ask the NEXT remaining question.\n")
                    .append("5. Only invoke the tool AFTER all applicable questions have been asked and answered (or if the caller explicitly says they don't know / want to skip an optional question).\n")
                    .append("6. If a tool execution returns VALIDATION_FAILED, immediately ask the caller the specific missing question mentioned in the result.\n")
                    .append("7. After tool execution succeeds, confirm politely in 1 short spoken sentence with the reference number.\n\n")
                    .append("ACTIVE BUSINESS TOOLS & DYNAMIC QUESTIONS:\n");

            for (ToolSpecification tool : tools) {
                fullSystemPrompt.append("========================================\n")
                        .append("TOOL: ").append(tool.name()).append("\n")
                        .append(tool.description()).append("\n")
                        .append("========================================\n\n");
            }
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

        messages.add(UserMessage.from(safeTranscript));

        int maxHops = 2;
        int currentHop = 0;
        StringBuilder finalResponse = new StringBuilder();
        boolean toolExecuted = false;
        Set<String> executedToolsInThisTurn = new HashSet<>();

        while (currentHop < maxHops) {
            AiRequest request = AiRequest.builder()
                    .messages(new ArrayList<>(messages))
                    .tools(tools.isEmpty() ? null : tools)
                    .tenantId(context.tenantId())
                    .complexity(AiRequest.TaskComplexity.LOW)
                    .maxTokens(55)
                    .temperature(0.3)
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

    /**
     * Determines whether the user transcript is a pure greeting, connection event, or phatic acknowledgement.
     * For such turns, tool schemas are stripped to allow free LLMs to respond in < 400ms without stalling.
     */
    private boolean isPhaticOrGreeting(String transcript) {
        if (transcript == null || transcript.isBlank()) {
            return true;
        }
        String clean = transcript.trim().toLowerCase().replaceAll("[^a-z0-9\\s]", " ").replaceAll("\\s+", " ").trim();
        if (clean.isEmpty()) {
            return true;
        }

        // Common greetings and short connection phrases
        if (clean.matches("^(hello|hi|hey|hola|namaste|good morning|good afternoon|good evening|hello i just connected|i just connected|can you hear me|am i audible|yes|no|yeah|yep|nope|okay|ok|sure|thanks|thank you|hello hello|hello hello hello)$")) {
            return true;
        }

        // Single or two-word repetitions of hello/hi
        String[] words = clean.split("\\s+");
        if (words.length <= 3) {
            boolean allPhatic = true;
            for (String w : words) {
                if (!w.equals("hello") && !w.equals("hi") && !w.equals("hey") && !w.equals("there") && !w.equals("yes") && !w.equals("ok") && !w.equals("okay")) {
                    allPhatic = false;
                    break;
                }
            }
            if (allPhatic) {
                return true;
            }
        }

        return false;
    }
}
