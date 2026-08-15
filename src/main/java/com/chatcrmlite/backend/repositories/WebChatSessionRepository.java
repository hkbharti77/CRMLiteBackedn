package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.WebChatSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WebChatSessionRepository extends JpaRepository<WebChatSession, UUID> {
    List<WebChatSession> findByOwnerOrderByUpdatedAtDesc(User owner);
    Optional<WebChatSession> findByOwnerAndId(User owner, UUID id);
    Optional<WebChatSession> findByOwnerAndSessionId(User owner, String sessionId);
    Optional<WebChatSession> findByOwnerAndSessionIdAndStatus(User owner, String sessionId, com.chatcrmlite.backend.models.SessionStatus status);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("UPDATE WebChatSession w SET w.status = 'PENDING_TIMEOUT', w.timeoutStartedAt = :now WHERE w.status = 'ACTIVE' AND w.lastActivityAt < :cutoff")
    int claimTimeout(java.time.LocalDateTime cutoff, java.time.LocalDateTime now);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("UPDATE WebChatSession w SET w.status = 'CLOSED', w.closedAt = :now, w.closeReason = 'HARD_TIMEOUT' WHERE w.status = 'PENDING_TIMEOUT' AND w.timeoutStartedAt < :cutoff")
    int closeHardTimeouts(java.time.LocalDateTime cutoff, java.time.LocalDateTime now);

    @org.springframework.data.jpa.repository.Query("SELECT w FROM WebChatSession w WHERE w.status = 'PENDING_TIMEOUT' AND w.timeoutStartedAt = :startedAt")
    List<WebChatSession> findByTimeoutStartedAt(java.time.LocalDateTime startedAt);
}
