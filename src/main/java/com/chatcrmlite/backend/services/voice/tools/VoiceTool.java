package com.chatcrmlite.backend.services.voice.tools;

import dev.langchain4j.agent.tool.ToolSpecification;

public interface VoiceTool {
    /**
     * Gets the tool specification to pass to the LLM.
     */
    ToolSpecification getSpecification();

    /**
     * The internal name of the tool (must match the name in ToolSpecification).
     */
    String getName();

    /**
     * Executes the tool with the given JSON arguments and context.
     * @param jsonArguments the arguments as a JSON string from the LLM
     * @param context the execution context
     * @return the execution result
     */
    ToolExecutionResult execute(String toolCallId, String jsonArguments, ToolExecutionContext context);
}
