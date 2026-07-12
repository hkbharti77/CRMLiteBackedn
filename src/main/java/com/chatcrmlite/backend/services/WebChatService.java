package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.WebChatMessage;
import com.chatcrmlite.backend.models.WebChatSession;
import com.chatcrmlite.backend.repositories.WebChatMessageRepository;
import com.chatcrmlite.backend.repositories.WebChatSessionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class WebChatService {

    @Autowired
    private WebChatSessionRepository sessionRepository;

    @Autowired
    private WebChatMessageRepository messageRepository;

    @Transactional
    public void saveMessage(User owner, String sessionId, WebChatMessage.Sender sender, String content) {
        if (owner == null || sessionId == null || content == null || content.isBlank()) {
            return;
        }

        WebChatSession session = sessionRepository.findByOwnerAndSessionId(owner, sessionId)
                .orElseGet(() -> sessionRepository.save(new WebChatSession(owner, sessionId)));

        // Update the timestamp on the session
        sessionRepository.save(session);

        WebChatMessage message = new WebChatMessage(session, sender, content);
        messageRepository.save(message);
    }

    @Transactional(readOnly = true)
    public List<WebChatSession> getAllSessions(User owner) {
        return sessionRepository.findByOwnerOrderByUpdatedAtDesc(owner);
    }

    @Transactional(readOnly = true)
    public WebChatSession getSession(UUID id, User owner) {
        return sessionRepository.findByOwnerAndId(owner, id).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<WebChatMessage> getSessionMessages(WebChatSession session) {
        return messageRepository.findBySessionOrderByCreatedAtAsc(session);
    }

    @Transactional
    public boolean deleteSession(UUID id, User owner) {
        return sessionRepository.findByOwnerAndId(owner, id).map(session -> {
            List<WebChatMessage> messages = messageRepository.findBySessionOrderByCreatedAtAsc(session);
            messageRepository.deleteAll(messages);
            sessionRepository.delete(session);
            return true;
        }).orElse(false);
    }
}
