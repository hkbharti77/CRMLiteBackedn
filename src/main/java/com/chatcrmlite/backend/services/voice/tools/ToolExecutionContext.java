package com.chatcrmlite.backend.services.voice.tools;

import java.util.UUID;

public record ToolExecutionContext(
    UUID tenantId,
    UUID userId,
    UUID callId,
    String streamId,
    String conversationId,
    String turnId,
    String callerPhone
) {}
