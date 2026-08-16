package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.ConversationState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface ConversationStateRepository extends JpaRepository<ConversationState, UUID> {

    Optional<ConversationState> findByContact(Contact contact);

    @Query("SELECT c FROM ConversationState c WHERE c.contact = :contact AND c.sessionStatus != 'CLOSED'")
    Optional<ConversationState> findActiveByContact(Contact contact);

    @Query("SELECT COUNT(c) > 0 FROM ConversationState c WHERE c.contact = :contact AND c.sessionStatus != 'CLOSED'")
    boolean existsActiveByContact(Contact contact);

    @Modifying
    @Query("UPDATE ConversationState c SET c.sessionStatus = 'CLOSED', c.closedAt = CURRENT_TIMESTAMP, c.closeReason = 'RESET' WHERE c.contact = :contact AND c.sessionStatus != 'CLOSED'")
    void closeActiveByContact(Contact contact);

    @Modifying
    @Query("UPDATE ConversationState c SET c.sessionStatus = 'PENDING_TIMEOUT', c.timeoutStartedAt = :now WHERE c.sessionStatus = 'ACTIVE' AND c.lastActivityAt < :cutoff")
    int claimTimeout(LocalDateTime cutoff, LocalDateTime now);

    @Modifying
    @Query("UPDATE ConversationState c SET c.sessionStatus = 'CLOSED', c.closedAt = :now, c.closeReason = 'HARD_TIMEOUT' WHERE c.sessionStatus = 'PENDING_TIMEOUT' AND c.timeoutStartedAt < :cutoff")
    int closeHardTimeouts(LocalDateTime cutoff, LocalDateTime now);

    /** Cleanup stale flows older than the given threshold (e.g., 24 hours). */
    @Modifying
    @Query("UPDATE ConversationState c SET c.sessionStatus = 'CLOSED', c.closedAt = :now, c.closeReason = 'STALE_FLOW' WHERE c.sessionStatus != 'CLOSED' AND c.lastUpdatedAt < :cutoff")
    void markStaleFlowsClosed(LocalDateTime cutoff, LocalDateTime now);
    
    @Query("SELECT c FROM ConversationState c WHERE c.sessionStatus = 'PENDING_TIMEOUT' AND c.timeoutStartedAt = :startedAt")
    java.util.List<ConversationState> findByTimeoutStartedAt(LocalDateTime startedAt);
}
