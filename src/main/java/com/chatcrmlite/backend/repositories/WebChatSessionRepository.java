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
}
