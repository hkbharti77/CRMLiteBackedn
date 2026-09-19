package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.AiActionLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AiActionLogRepository extends JpaRepository<AiActionLog, UUID> {

    @Query("SELECT a FROM AiActionLog a WHERE a.tenant.id = :tenantId ORDER BY a.createdAt DESC")
    List<AiActionLog> findByTenantIdOrderByCreatedAtDesc(@Param("tenantId") UUID tenantId);

    Optional<AiActionLog> findByProviderMessageId(String providerMessageId);

    Optional<AiActionLog> findByIdempotencyKey(String idempotencyKey);

    @Query("SELECT a FROM AiActionLog a WHERE a.tenant.id = :tenantId AND a.contactId = :contactId AND a.catalog.id = :catalogId AND a.executed = true AND a.createdAt >= :since ORDER BY a.createdAt DESC")
    List<AiActionLog> findRecentDispatches(
            @Param("tenantId") UUID tenantId,
            @Param("contactId") UUID contactId,
            @Param("catalogId") UUID catalogId,
            @Param("since") LocalDateTime since
    );
}
