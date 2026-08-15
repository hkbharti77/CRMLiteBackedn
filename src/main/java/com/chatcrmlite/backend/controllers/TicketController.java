package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.dto.TicketDTO;
import com.chatcrmlite.backend.dto.TicketRequest;
import com.chatcrmlite.backend.models.Ticket;
import com.chatcrmlite.backend.models.TicketActivity;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.TicketService;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tickets")
public class TicketController {

    @Autowired
    private TicketService ticketService;

    @Autowired
    private UserRepository userRepository;

    private User getAuthenticatedUser() {
        String email = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    @GetMapping
    public ResponseEntity<List<TicketDTO>> getTickets(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size
    ) {
        User user = getAuthenticatedUser();
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<TicketDTO> resultPage;
        if (search != null && !search.trim().isEmpty()) {
            resultPage = ticketService.searchTickets(user, search.trim(), pageable);
        } else if (status != null && !status.trim().isEmpty() && !"ALL".equalsIgnoreCase(status)) {
            try {
                Ticket.TicketStatus ticketStatus = Ticket.TicketStatus.valueOf(status.toUpperCase());
                resultPage = ticketService.getTicketsByStatus(user, ticketStatus, pageable);
            } catch (IllegalArgumentException e) {
                resultPage = ticketService.getAllTickets(user, pageable);
            }
        } else {
            resultPage = ticketService.getAllTickets(user, pageable);
        }

        return ResponseEntity.ok(resultPage.getContent());
    }

    @GetMapping("/paged")
    public ResponseEntity<Page<TicketDTO>> getTicketsPaged(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        User user = getAuthenticatedUser();
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<TicketDTO> resultPage;
        if (search != null && !search.trim().isEmpty()) {
            resultPage = ticketService.searchTickets(user, search.trim(), pageable);
        } else if (status != null && !status.trim().isEmpty() && !"ALL".equalsIgnoreCase(status)) {
            try {
                Ticket.TicketStatus ticketStatus = Ticket.TicketStatus.valueOf(status.toUpperCase());
                resultPage = ticketService.getTicketsByStatus(user, ticketStatus, pageable);
            } catch (IllegalArgumentException e) {
                resultPage = ticketService.getAllTickets(user, pageable);
            }
        } else {
            resultPage = ticketService.getAllTickets(user, pageable);
        }

        return ResponseEntity.ok(resultPage);
    }

    @GetMapping("/{id}")
    public ResponseEntity<TicketDTO> getTicket(@PathVariable UUID id) {
        User user = getAuthenticatedUser();
        return ResponseEntity.ok(ticketService.getTicket(id, user));
    }

    @PostMapping
    public ResponseEntity<TicketDTO> createTicket(@RequestBody TicketRequest request) {
        User user = getAuthenticatedUser();
        if (request.getSubmitterName() == null || request.getSubmitterName().isEmpty()) {
            request.setSubmitterName(user.getDisplayName() != null ? user.getDisplayName() : user.getEmail());
        }
        if (request.getSubmitterEmail() == null || request.getSubmitterEmail().isEmpty()) {
            request.setSubmitterEmail(user.getEmail());
        }
        Ticket ticket = ticketService.createTicket(user, request);
        return ResponseEntity.ok(ticketService.toDTO(ticket));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<TicketDTO> updateStatus(
            @PathVariable UUID id,
            @RequestParam(required = false) String status,
            @RequestBody(required = false) Map<String, String> body
    ) {
        User user = getAuthenticatedUser();
        String targetStatus = status;
        if (targetStatus == null && body != null) {
            targetStatus = body.get("status");
        }
        if (targetStatus == null) {
            return ResponseEntity.badRequest().build();
        }
        Ticket.TicketStatus newStatus = Ticket.TicketStatus.valueOf(targetStatus.toUpperCase());
        return ResponseEntity.ok(ticketService.updateStatus(id, newStatus, user));
    }

    @PatchMapping("/{id}/priority")
    public ResponseEntity<TicketDTO> updatePriority(
            @PathVariable UUID id,
            @RequestParam(required = false) String priority,
            @RequestBody(required = false) Map<String, String> body
    ) {
        User user = getAuthenticatedUser();
        String targetPriority = priority;
        if (targetPriority == null && body != null) {
            targetPriority = body.get("priority");
        }
        if (targetPriority == null) {
            return ResponseEntity.badRequest().build();
        }
        Ticket.TicketPriority newPriority = Ticket.TicketPriority.valueOf(targetPriority.toUpperCase());
        return ResponseEntity.ok(ticketService.updatePriority(id, newPriority, user));
    }

    @PatchMapping("/{id}/assign")
    public ResponseEntity<TicketDTO> assignTicket(
            @PathVariable UUID id,
            @RequestParam(required = false) UUID agentId,
            @RequestBody(required = false) Map<String, String> body
    ) {
        User user = getAuthenticatedUser();
        UUID targetAgentId = agentId;
        if (targetAgentId == null && body != null && body.containsKey("agentId")) {
            targetAgentId = UUID.fromString(body.get("agentId"));
        }
        if (targetAgentId == null) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(ticketService.assignTicket(id, targetAgentId, user));
    }

    @Data
    public static class CommentRequest {
        private String message;
        private boolean internal = false;
    }

    @PostMapping("/{id}/comments")
    public ResponseEntity<TicketDTO> addComment(
            @PathVariable UUID id,
            @RequestBody CommentRequest request
    ) {
        User user = getAuthenticatedUser();
        return ResponseEntity.ok(ticketService.addComment(id, user, request.getMessage(), request.isInternal()));
    }

    @GetMapping("/{id}/messages")
    public ResponseEntity<TicketDTO> getTicketMessages(@PathVariable UUID id) {
        User user = getAuthenticatedUser();
        return ResponseEntity.ok(ticketService.getTicket(id, user));
    }

    @PostMapping("/{id}/messages")
    public ResponseEntity<TicketDTO> addTicketMessage(
            @PathVariable UUID id,
            @RequestBody CommentRequest request
    ) {
        User user = getAuthenticatedUser();
        return ResponseEntity.ok(ticketService.addComment(id, user, request.getMessage(), request.isInternal()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTicket(@PathVariable UUID id) {
        User user = getAuthenticatedUser();
        ticketService.deleteTicket(id, user);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/activities")
    public ResponseEntity<List<TicketActivity>> getActivities(@PathVariable UUID id) {
        User user = getAuthenticatedUser();
        return ResponseEntity.ok(ticketService.getTicketActivities(id, user));
    }

    @GetMapping("/open-count")
    public ResponseEntity<Map<String, Long>> getOpenCount() {
        User user = getAuthenticatedUser();
        long count = ticketService.countOpenTickets(user);
        return ResponseEntity.ok(Map.of("count", count));
    }
}
