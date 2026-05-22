package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.dto.AddCommentRequest;
import com.chatcrmlite.backend.dto.TicketDTO;
import com.chatcrmlite.backend.dto.TicketRequest;
import com.chatcrmlite.backend.models.Ticket;
import com.chatcrmlite.backend.models.TicketActivity;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.TicketService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Authenticated REST endpoints for the Customer Ticketing System.
 *
 * All paths are under /api/v1/tickets — requires JWT authentication.
 *
 * Endpoints:
 *   GET    /api/v1/tickets                          — list all tickets (paginated)
 *   GET    /api/v1/tickets/search?q=query           — search tickets
 *   GET    /api/v1/tickets/{id}                     — get single ticket
 *   GET    /api/v1/tickets/number/{ticketNumber}    — get by ticket number
 *   POST   /api/v1/tickets                          — create ticket manually
 *   PATCH  /api/v1/tickets/{id}/status              — update status
 *   PATCH  /api/v1/tickets/{id}/priority            — update priority
 *   PATCH  /api/v1/tickets/{id}/assign              — assign to agent
 *   POST   /api/v1/tickets/{id}/comments            — add comment
 *   DELETE /api/v1/tickets/{id}                     — soft delete
 *   GET    /api/v1/tickets/stats                    — open ticket count
 *   GET    /api/v1/tickets/{id}/activities          — get audit log
 */
@RestController
@RequestMapping("/api/v1/tickets")
public class TicketController {

    @Autowired private TicketService ticketService;
    @Autowired private UserRepository userRepository;

    private User getAuthenticatedUser() {
        String email = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    // ── List (Paginated) ───────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<Page<TicketDTO>> getTickets(
            @RequestParam(required = false) Ticket.TicketStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        
        User owner = getAuthenticatedUser();
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        
        Page<TicketDTO> tickets = status != null
                ? ticketService.getTicketsByStatus(owner, status, pageable)
                : ticketService.getAllTickets(owner, pageable);
        
        return ResponseEntity.ok(tickets);
    }

    // ── Search ─────────────────────────────────────────────────────────────

    @GetMapping("/search")
    public ResponseEntity<Page<TicketDTO>> searchTickets(
            @RequestParam String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        
        User owner = getAuthenticatedUser();
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<TicketDTO> tickets = ticketService.searchTickets(owner, q, pageable);
        
        return ResponseEntity.ok(tickets);
    }

    // ── Single ─────────────────────────────────────────────────────────────

    @GetMapping("/{id}")
    public ResponseEntity<TicketDTO> getTicket(@PathVariable UUID id) {
        return ResponseEntity.ok(ticketService.getTicket(id, getAuthenticatedUser()));
    }

    @GetMapping("/number/{ticketNumber}")
    public ResponseEntity<TicketDTO> getTicketByNumber(@PathVariable String ticketNumber) {
        return ResponseEntity.ok(ticketService.getTicketByNumber(ticketNumber, getAuthenticatedUser()));
    }

    // ── Create ─────────────────────────────────────────────────────────────

    @PostMapping
    public ResponseEntity<TicketDTO> createTicket(@Valid @RequestBody TicketRequest req) {
        Ticket ticket = ticketService.createTicket(getAuthenticatedUser(), req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ticketService.toDTO(ticket));
    }

    // ── Status update ──────────────────────────────────────────────────────

    @PatchMapping("/{id}/status")
    public ResponseEntity<TicketDTO> updateStatus(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body) {
        Ticket.TicketStatus status = Ticket.TicketStatus.valueOf(body.get("status"));
        return ResponseEntity.ok(ticketService.updateStatus(id, status, getAuthenticatedUser()));
    }

    // ── Priority update ────────────────────────────────────────────────────

    @PatchMapping("/{id}/priority")
    public ResponseEntity<TicketDTO> updatePriority(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body) {
        Ticket.TicketPriority priority = Ticket.TicketPriority.valueOf(body.get("priority"));
        return ResponseEntity.ok(ticketService.updatePriority(id, priority, getAuthenticatedUser()));
    }

    // ── Assign ─────────────────────────────────────────────────────────────

    @PatchMapping("/{id}/assign")
    public ResponseEntity<TicketDTO> assignTicket(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body) {
        UUID agentId = UUID.fromString(body.get("agentId"));
        return ResponseEntity.ok(ticketService.assignTicket(id, agentId, getAuthenticatedUser()));
    }

    // ── Comments ───────────────────────────────────────────────────────────

    @PostMapping("/{id}/comments")
    public ResponseEntity<TicketDTO> addComment(
            @PathVariable UUID id,
            @Valid @RequestBody AddCommentRequest req) {
        User owner = getAuthenticatedUser();
        boolean internal = req.getInternal() != null && req.getInternal();
        return ResponseEntity.ok(ticketService.addComment(id, owner, req.getMessage(), internal));
    }

    // ── Delete ─────────────────────────────────────────────────────────────

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTicket(@PathVariable UUID id) {
        ticketService.deleteTicket(id, getAuthenticatedUser());
        return ResponseEntity.noContent().build();
    }

    // ── Stats ──────────────────────────────────────────────────────────────

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Long>> getStats() {
        User owner = getAuthenticatedUser();
        long openCount = ticketService.countOpenTickets(owner);
        return ResponseEntity.ok(Map.of("openTickets", openCount));
    }

    // ── Activities (Audit Log) ─────────────────────────────────────────────

    @GetMapping("/{id}/activities")
    public ResponseEntity<List<TicketActivity>> getActivities(@PathVariable UUID id) {
        return ResponseEntity.ok(ticketService.getTicketActivities(id, getAuthenticatedUser()));
    }
}
