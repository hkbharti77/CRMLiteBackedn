package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.WebChatMessage;
import com.chatcrmlite.backend.models.WebChatSession;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.WebChatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/webchat")
public class WebChatController {

    @Autowired
    private WebChatService webChatService;

    @Autowired
    private UserRepository userRepository;

    private User getAuthenticatedUser() {
        String email = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    @GetMapping("/sessions")
    public ResponseEntity<List<Map<String, Object>>> getAllSessions() {
        User owner = getAuthenticatedUser();
        List<WebChatSession> sessions = webChatService.getAllSessions(owner);

        List<Map<String, Object>> result = sessions.stream().map(s -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", s.getId().toString());
            map.put("sessionId", s.getSessionId());
            map.put("createdAt", s.getCreatedAt());
            map.put("updatedAt", s.getUpdatedAt());
            map.put("status", s.getStatus());

            Optional<WebChatMessage> lastMsgOpt = webChatService.getLastMessage(s);
            if (lastMsgOpt.isPresent()) {
                WebChatMessage lastMsg = lastMsgOpt.get();
                map.put("lastMessage", lastMsg.getContent());
                map.put("lastMessageSender", lastMsg.getSender().name());
            } else {
                map.put("lastMessage", "No messages yet");
                map.put("lastMessageSender", "BOT");
            }
            return map;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    @GetMapping("/sessions/{id}")
    public ResponseEntity<Map<String, Object>> getSessionDetails(@PathVariable UUID id) {
        User owner = getAuthenticatedUser();
        WebChatSession session = webChatService.getSession(id, owner);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }
        
        List<WebChatMessage> messages = webChatService.getSessionMessages(session);
        return ResponseEntity.ok(Map.of(
            "session", session,
            "messages", messages
        ));
    }

    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<Map<String, String>> deleteSession(@PathVariable UUID id) {
        User owner = getAuthenticatedUser();
        boolean deleted = webChatService.deleteSession(id, owner);
        
        if (deleted) {
            return ResponseEntity.ok(Map.of("message", "Session deleted successfully"));
        } else {
            return ResponseEntity.notFound().build();
        }
    }
}
