package com.chatcrmlite.backend.dto.ai.action;

import java.util.UUID;

public record AiAction(
        AiActionType type,
        UUID catalogId,
        String reason,
        String caption,
        DecisionSource decisionSource
) {
    public enum AiActionType {
        SEND_CATALOG,
        CLARIFY,
        NONE
    }

    public enum DecisionSource {
        NATIVE_TOOL,
        STRUCTURED_FALLBACK,
        TEST_SIMULATION
    }
}
