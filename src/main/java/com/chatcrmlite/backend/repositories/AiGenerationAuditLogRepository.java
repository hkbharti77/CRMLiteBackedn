package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.flows.AiGenerationAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface AiGenerationAuditLogRepository extends JpaRepository<AiGenerationAuditLog, UUID> {
}
