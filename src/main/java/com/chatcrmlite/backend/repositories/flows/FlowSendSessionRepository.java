package com.chatcrmlite.backend.repositories.flows;

import com.chatcrmlite.backend.models.flows.FlowSendSession;
import com.chatcrmlite.backend.models.flows.FlowSendSessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FlowSendSessionRepository extends JpaRepository<FlowSendSession, UUID> {

    /**
     * Tenant-scoped lookup by flow_token.
     *
     * <p>Always use this method at webhook ingress — never look up by token alone.
     * Authorization must not rely on token secrecy; the tenant boundary is enforced here.
     *
     * @param tenantId the tenant of the webhook being processed
     * @param flowToken the token extracted from nfm_reply.response_json.flow_token
     */
    Optional<FlowSendSession> findByTenant_IdAndFlowToken(UUID tenantId, String flowToken);

    /**
     * Used by the TTL expiry sweeper to close stale sessions.
     */
    List<FlowSendSession> findByStatusInAndExpiresAtBefore(
            List<FlowSendSessionStatus> statuses, LocalDateTime cutoff);
}
