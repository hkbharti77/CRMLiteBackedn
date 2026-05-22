package com.chatcrmlite.backend.services.ai.guardrail;

import com.chatcrmlite.backend.dto.ai.GuardrailResult;
import java.util.UUID;

public interface GuardrailService {
    GuardrailResult evaluate(String rawText, String userId, boolean lastIsAi, String niche, UUID tenantId);
}
