package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.models.PlatformTicket;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.PlatformTicketMessage;
import com.chatcrmlite.backend.repositories.PlatformTicketRepository;
import com.chatcrmlite.backend.repositories.PlatformTicketMessageRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.EmailService;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/tickets")
public class TicketController {

    @Autowired
    private PlatformTicketRepository ticketRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlatformTicketMessageRepository messageRepository;
    
    @Autowired
    private EmailService emailService;

    @Data
    public static class TicketCreateRequest {
        private String title;
        private String description;
    }

    @Data
    public static class MessageCreateRequest {
        private String message;
    }

    private User getAuthenticatedUser() {
        String email = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    @PostMapping
    public ResponseEntity<PlatformTicket> createTicket(@RequestBody TicketCreateRequest request) {
        User user = getAuthenticatedUser();
        
        PlatformTicket ticket = new PlatformTicket();
        ticket.setTenantId(user.getTenant().getId().toString());
        ticket.setTitle(request.getTitle());
        ticket.setDescription(request.getDescription());
        ticket.setStatus("OPEN");
        ticket.setSubmittedByEmail(user.getEmail());
        
        PlatformTicket savedTicket = ticketRepository.save(ticket);
        
        // Also add the original request as the first chat message
        PlatformTicketMessage msg = new PlatformTicketMessage();
        msg.setTicketId(savedTicket.getId());
        msg.setSenderType("TENANT");
        msg.setSenderEmail(user.getEmail());
        msg.setMessage("**" + request.getTitle() + "**\n\n" + request.getDescription());
        messageRepository.save(msg);
        
        try {
            String displayName = user.getDisplayName() != null ? user.getDisplayName() : user.getEmail();
            emailService.sendPlatformTicketCreatedNotification(
                user.getEmail(), 
                displayName, 
                savedTicket.getId(), 
                request.getTitle(), 
                request.getDescription()
            );
        } catch (Exception e) {
            // Log error but don't fail the request
            e.printStackTrace();
        }
        
        return ResponseEntity.ok(savedTicket);
    }
    
    @GetMapping
    public ResponseEntity<List<PlatformTicket>> getTenantTickets() {
        User user = getAuthenticatedUser();
        List<PlatformTicket> tickets = ticketRepository.findByTenantIdOrderByCreatedAtDesc(user.getTenant().getId().toString());
        return ResponseEntity.ok(tickets);
    }

    @GetMapping("/{id}/messages")
    public ResponseEntity<List<PlatformTicketMessage>> getTicketMessages(@PathVariable String id) {
        User user = getAuthenticatedUser();
        PlatformTicket ticket = ticketRepository.findById(id).orElse(null);
        if (ticket == null || !ticket.getTenantId().equals(user.getTenant().getId().toString())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(messageRepository.findByTicketIdOrderByCreatedAtAsc(id));
    }

    @PostMapping("/{id}/messages")
    public ResponseEntity<PlatformTicketMessage> addTicketMessage(@PathVariable String id, @RequestBody MessageCreateRequest request) {
        User user = getAuthenticatedUser();
        PlatformTicket ticket = ticketRepository.findById(id).orElse(null);
        if (ticket == null || !ticket.getTenantId().equals(user.getTenant().getId().toString())) {
            return ResponseEntity.notFound().build();
        }

        PlatformTicketMessage msg = new PlatformTicketMessage();
        msg.setTicketId(id);
        msg.setSenderType("TENANT");
        msg.setSenderEmail(user.getEmail());
        msg.setMessage(request.getMessage());

        return ResponseEntity.ok(messageRepository.save(msg));
    }
}
