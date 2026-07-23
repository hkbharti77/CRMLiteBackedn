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

        List<Contact> contacts = contactRepository.findAll().stream()
                .filter(c -> c.getOwner() != null && c.getOwner().getId().equals(user.getId()))
                .filter(c -> c.getWaId() != null && !c.getWaId().startsWith("web:"))
                .collect(Collectors.toList());

        List<Map<String, Object>> chatList = contacts.stream().map(contact -> {
            List<Message> messages = messageRepository.findAllByContactOrderByTimestampAsc(contact);
            Message lastMessage = messages.isEmpty() ? null : messages.get(messages.size() - 1);

            Map<String, Object> chat = new HashMap<>();
            chat.put("id", contact.getId());
            chat.put("name", contact.getName() != null ? contact.getName() : contact.getWaId());
            chat.put("lastMessage", lastMessage != null ? lastMessage.getContent() : "No messages yet");
            chat.put("time", lastMessage != null ? lastMessage.getTimestamp().toString() : "");
            chat.put("unread", 0); // Logic for unread can be added later
            chat.put("status", "NEW"); // Pull from Lead if available
            return chat;
        }).collect(Collectors.toList());

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
                .filter(c -> c.getOwner().getId().equals(user.getId()))
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Contact not found or access denied"));

        List<Message> history = messageRepository.findAllByContactOrderByTimestampAsc(contact);
        return ResponseEntity.ok(history);
    }

    @PostMapping("/{contactId}")
    public ResponseEntity<Void> sendMessage(
            @PathVariable UUID contactId,
            @RequestBody Map<String, String> request,
            @AuthenticationPrincipal String email) {

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "User not found"));

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
