package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.ConversationState;
import com.chatcrmlite.backend.models.TenantFlowConfig;
import com.chatcrmlite.backend.models.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface TenantFlowConfigRepository extends JpaRepository<TenantFlowConfig, Long> {
    Optional<TenantFlowConfig> findByTenantAndFlowType(User tenant, ConversationState.FlowType flowType);

    @Modifying
    @Transactional
    @Query("UPDATE TenantFlowConfig t SET t.intentDescription = :intentDesc, t.triggerExamples = :triggers, t.updatedAt = CURRENT_TIMESTAMP WHERE t.tenant = :tenant AND t.flowType = :flowType")
    int updateIntentConfig(@Param("tenant") User tenant, @Param("flowType") ConversationState.FlowType flowType, @Param("intentDesc") String intentDesc, @Param("triggers") List<String> triggers);
}
