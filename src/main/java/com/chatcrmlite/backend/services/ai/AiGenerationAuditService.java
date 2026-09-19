package com.chatcrmlite.backend.services.ai;

import com.chatcrmlite.backend.models.flows.AiGenerationAuditLog;
import com.chatcrmlite.backend.repositories.AiGenerationAuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiGenerationAuditService {

    private final AiGenerationAuditLogRepository auditRepository;

    public void logGeneration(String model, String requestId, String status, Integer tokensUsed, Long latencyMs, String errorMessage) {
        try {
            AiGenerationAuditLog auditLog = AiGenerationAuditLog.builder()
                    .model(model)
                    .requestId(requestId)
                    .status(status)
                    .tokensUsed(tokensUsed)
                    .latencyMs(latencyMs)
                    .errorMessage(errorMessage)
                    .build();
            
            // Note: TenantId is automatically populated by BaseTenantEntity from the SecurityContext
            auditRepository.save(auditLog);
        } catch (Exception e) {
            log.error("Failed to save AI generation audit log", e);
        }
    }
}
