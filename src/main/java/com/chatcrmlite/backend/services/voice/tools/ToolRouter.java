package com.chatcrmlite.backend.services.voice.tools;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ToolRouter {
    private static final Logger log = LoggerFactory.getLogger(ToolRouter.class);

    private final ToolRegistry toolRegistry;
    private final ToolIdempotencyService idempotencyService;

    public ToolRouter(ToolRegistry toolRegistry, ToolIdempotencyService idempotencyService) {
        this.toolRegistry = toolRegistry;
        this.idempotencyService = idempotencyService;
    }

    public ToolExecutionResult execute(ToolExecutionRequest request, ToolExecutionContext context) {
        String toolName = request.name();
        String toolCallId = request.id();
        String arguments = request.arguments();

        // 1. Authorization Check
        if (!toolRegistry.isToolEnabledForTenant(toolName, context.tenantId())) {
            log.warn("Unauthorized tool execution attempt. Tool: {}, Tenant: {}", toolName, context.tenantId());
            return new ToolExecutionResult(toolName, toolCallId, ToolExecutionStatus.UNAUTHORIZED, "Tool is not authorized for this tenant.", "UNAUTHORIZED");
        }

        VoiceTool tool = toolRegistry.getTool(toolName);
        if (tool == null) {
            log.error("Tool not found: {}", toolName);
            return new ToolExecutionResult(toolName, toolCallId, ToolExecutionStatus.FAILED, "Tool not found.", "NOT_FOUND");
        }

        // 2. Idempotency Check
        String idempotencyKey = idempotencyService.generateKey(context, toolCallId);
        ToolExecutionResult cached = idempotencyService.getCachedResult(idempotencyKey);
        if (cached != null) {
            log.info("Returning cached result for tool call: {}", idempotencyKey);
            return cached;
        }

        // 3. Execution
        try {
            ToolExecutionResult result = tool.execute(toolCallId, arguments, context);
            // 4. Cache successful or definitive results (don't cache UNKNOWN or TIMEOUT as we might retry)
            if (result.status() == ToolExecutionStatus.SUCCESS || result.status() == ToolExecutionStatus.VALIDATION_FAILED || result.status() == ToolExecutionStatus.FAILED) {
                idempotencyService.cacheResult(idempotencyKey, result);
            }
            return result;
        } catch (Exception e) {
            log.error("Exception executing tool {}", toolName, e);
            // We return UNKNOWN because the external state mutation might have succeeded before failing
            return new ToolExecutionResult(toolName, toolCallId, ToolExecutionStatus.UNKNOWN, "An unknown error occurred during execution.", "INTERNAL_ERROR");
        }
    }
}
