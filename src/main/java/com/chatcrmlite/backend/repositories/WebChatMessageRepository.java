package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.WebChatMessage;
import com.chatcrmlite.backend.models.WebChatSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WebChatMessageRepository extends JpaRepository<WebChatMessage, UUID> {
    List<WebChatMessage> findBySessionOrderByCreatedAtAsc(WebChatSession session);
    
    // For Conversation Memory (Recent Turns)
    List<WebChatMessage> findTop50BySessionOrderByCreatedAtDesc(WebChatSession session);
}
