package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Message;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.MessageRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.whatsapp.WhatsAppMessageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/messages")
public class MessageController {

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private ContactRepository contactRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WhatsAppMessageService whatsappMessageService;

    @Autowired
    private com.chatcrmlite.backend.services.ContactService contactService;

    @GetMapping("/chats")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> getActiveChats(@AuthenticationPrincipal String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        List<Contact> contacts;
        if (user.getTenant() != null) {
            contacts = contactRepository.findAllByTenant(user.getTenant()).stream()
                    .filter(c -> c.getWaId() != null && !c.getWaId().startsWith("web:"))
                    .filter(c -> {
                        if (user.getRole() == User.Role.AGENT) {
                            // Agents see chats assigned to them OR unassigned chats in their tenant
                            return c.getAssignedAgent() == null || c.getAssignedAgent().getId().equals(user.getId());
                        }
                        return true; // Admins and Owners see all tenant chats
                    })
                    .collect(Collectors.toList());
        } else {
            contacts = contactRepository.findAllByOwner(user).stream()
                    .filter(c -> c.getWaId() != null && !c.getWaId().startsWith("web:"))
                    .collect(Collectors.toList());
        }

        List<Map<String, Object>> chatList = contacts.stream()
                .map(contact -> {
                    List<Message> messages = messageRepository.findAllByContactOrderByTimestampAsc(contact);
                    if (messages == null || messages.isEmpty()) {
                        return null; // Only show active sessions with actual chat history
                    }
                    Message lastMessage = messages.get(messages.size() - 1);

                    Map<String, Object> chat = new HashMap<>();
                    chat.put("id", contact.getId());
                    chat.put("name", contact.getName() != null && !contact.getName().isBlank() ? contact.getName() : contact.getWaId());
                    chat.put("phone", contact.getWaId());
                    chat.put("lastMessage", lastMessage.getContent() != null ? lastMessage.getContent() : "");
                    chat.put("time", lastMessage.getTimestamp() != null ? lastMessage.getTimestamp().toString() : "");
                    chat.put("unread", 0);
                    chat.put("status", "NEW");
                    chat.put("botPaused", contact.isBotPaused());
                    chat.put("messageCount", messages.size());
                    chat.put("supportState", contact.getSupportState() != null ? contact.getSupportState().name() : "IDLE");
                    chat.put("assignedAgentId", contact.getAssignedAgent() != null ? contact.getAssignedAgent().getId() : null);
                    return chat;
                })
                .filter(Objects::nonNull)
                .sorted((a, b) -> {
                    String timeA = String.valueOf(a.getOrDefault("time", ""));
                    String timeB = String.valueOf(b.getOrDefault("time", ""));
                    return timeB.compareTo(timeA); // Most recent chat session first
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(chatList);
    }

    @GetMapping("/{contactId}")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<List<Message>> getMessageHistory(
            @PathVariable UUID contactId,
            @AuthenticationPrincipal String email) {

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "User not found"));

        Contact contact = contactRepository.findById(contactId)
                .filter(c -> (c.getTenant() != null && user.getTenant() != null && c.getTenant().getId().equals(user.getTenant().getId()))
                          || (c.getOwner() != null && c.getOwner().getId().equals(user.getId())))
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Contact not found or access denied"));

        List<Message> history = messageRepository.findAllByContactOrderByTimestampAsc(contact);
        return ResponseEntity.ok(history);
    }

    @Autowired
    private com.chatcrmlite.backend.services.livechat.LiveChatAuthorizationService authorizationService;

    @PostMapping("/{contactId}")
    public ResponseEntity<Void> sendMessage(
            @PathVariable UUID contactId,
            @RequestBody Map<String, String> request,
            @AuthenticationPrincipal String email) {

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "User not found"));

        Contact contact = contactRepository.findById(contactId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Contact not found"));

        if (!authorizationService.canSendMessage(contact, user)) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "Chat is locked by another agent");
        }

        String text = request.get("text");
        whatsappMessageService.sendMessage(contactId, text, user);
        contactService.toggleBotPaused(contactId, true, user);

        return ResponseEntity.ok().build();
    }

    @PostMapping("/{contactId}/menu")
    public ResponseEntity<String> sendMenu(
            @PathVariable UUID contactId,
            @AuthenticationPrincipal String email) {

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        try {
            whatsappMessageService.sendTenantMenu(contactId, user);
            return ResponseEntity.ok("Menu sent successfully");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Failed to send menu: " + e.getMessage());
        }
    }
}
