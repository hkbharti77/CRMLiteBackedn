package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.ConversationState;
import com.chatcrmlite.backend.models.FlowDefinition;
import com.chatcrmlite.backend.models.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface FlowDefinitionRepository extends JpaRepository<FlowDefinition, UUID> {

    @Query("SELECT f FROM FlowDefinition f WHERE f.tenant = :tenant AND f.flowType = :flowType AND f.isActive = true ORDER BY f.version DESC LIMIT 1")
    Optional<FlowDefinition> findLatestActiveByTenantAndFlowType(User tenant, ConversationState.FlowType flowType);

    @Query("SELECT f FROM FlowDefinition f WHERE f.tenant IS NULL AND f.flowType = :flowType AND f.isActive = true ORDER BY f.version DESC LIMIT 1")
    Optional<FlowDefinition> findLatestActiveGlobalByFlowType(ConversationState.FlowType flowType);
}
