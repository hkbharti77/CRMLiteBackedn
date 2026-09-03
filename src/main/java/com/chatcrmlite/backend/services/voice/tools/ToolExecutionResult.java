package com.chatcrmlite.backend.services.voice.tools;

public record ToolExecutionResult(
    String toolName,
    String toolCallId,
    ToolExecutionStatus status,
    String result,
    String errorCode
) {
    public boolean isSuccess() {
        return status == ToolExecutionStatus.SUCCESS;
    }
}
