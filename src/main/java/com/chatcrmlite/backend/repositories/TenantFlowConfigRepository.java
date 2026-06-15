package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.ConversationState;
import com.chatcrmlite.backend.models.TenantFlowConfig;
import com.chatcrmlite.backend.models.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TenantFlowConfigRepository extends JpaRepository<TenantFlowConfig, Long> {
    Optional<TenantFlowConfig> findByTenantAndFlowType(User tenant, ConversationState.FlowType flowType);
}
