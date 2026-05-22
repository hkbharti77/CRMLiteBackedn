package com.chatcrmlite.backend.cqrs.projections;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ConversationSummaryRepository extends JpaRepository<ConversationSummary, UUID> {
    List<ConversationSummary> findByTenantIdOrderByLastUpdatedAtDesc(UUID tenantId);
}
