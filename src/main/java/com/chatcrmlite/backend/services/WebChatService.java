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
import java.time.LocalDateTime;
import com.chatcrmlite.backend.models.SessionStatus;
import org.springframework.scheduling.annotation.Scheduled;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

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

        // Session Timeout Logic
        if (session.getStatus() == SessionStatus.CLOSED) {
            // Re-open or create a new active session
            session.setStatus(SessionStatus.ACTIVE);
            session.setCloseReason(null);
        } else if (session.getStatus() == SessionStatus.PENDING_TIMEOUT) {
            // Any legitimate user interaction cancels the pending timeout
            session.setStatus(SessionStatus.ACTIVE);
            session.setTimeoutStartedAt(null);
        }

        // Update activity
        session.setLastActivityAt(LocalDateTime.now());
        sessionRepository.save(session);

        WebChatMessage message = new WebChatMessage(session, sender, content);
        messageRepository.save(message);
    }
    
    @Scheduled(fixedDelay = 60000) // check every 1 minute
    @SchedulerLock(name = "WebChatService_processTimeouts", lockAtMostFor = "5m", lockAtLeastFor = "30s")
    @Transactional
    public void processTimeouts() {
        LocalDateTime now = LocalDateTime.now();
        // 1. Claim inactive sessions (30 mins inactivity)
        LocalDateTime inactiveCutoff = now.minusMinutes(30);
        int claimed = sessionRepository.claimTimeout(inactiveCutoff, now);
        
        if (claimed > 0) {
            // Find the ones we just claimed
            List<WebChatSession> pendingSessions = sessionRepository.findByTimeoutStartedAt(now);
            for (WebChatSession session : pendingSessions) {
                // Send the interactive timeout message
                String timeoutPrompt = "Would you like to connect with our team or ask another question?";
                // Here we save a system message to the session so the web client displays the buttons
                WebChatMessage msg = new WebChatMessage(session, WebChatMessage.Sender.BOT, timeoutPrompt);
                // Note: The actual structured buttons payload would be handled by the frontend or 
                // a structured message type if supported. Assuming text for now.
                messageRepository.save(msg);
            }
        }
        
        // 2. Close hard timeouts (15 mins after pending timeout)
        LocalDateTime hardCloseCutoff = now.minusMinutes(15);
        int closed = sessionRepository.closeHardTimeouts(hardCloseCutoff, now);
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
