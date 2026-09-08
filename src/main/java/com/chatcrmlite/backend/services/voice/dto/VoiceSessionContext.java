package com.chatcrmlite.backend.services.voice.dto;

import dev.langchain4j.agent.tool.ToolSpecification;
import java.io.Serializable;
import java.util.List;
import java.util.UUID;

public record VoiceSessionContext(
    UUID tenantId,
    String assistantName,
    String greetingText,
    String personaPrompt,
    List<ToolSpecification> toolSpecifications
) implements Serializable {}
