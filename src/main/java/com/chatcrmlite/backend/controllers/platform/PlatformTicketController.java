package com.chatcrmlite.backend.controllers.platform;

import com.chatcrmlite.backend.models.PlatformTicket;
import com.chatcrmlite.backend.models.PlatformTicketMessage;
import com.chatcrmlite.backend.repositories.PlatformTicketRepository;
import com.chatcrmlite.backend.repositories.PlatformTicketMessageRepository;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/platform/tickets")
public class PlatformTicketController {

    @Autowired
    private PlatformTicketRepository ticketRepository;

    @Autowired
    private PlatformTicketMessageRepository messageRepository;

    @GetMapping
    public ResponseEntity<List<PlatformTicket>> getAllTickets() {
        return ResponseEntity.ok(ticketRepository.findAllByOrderByCreatedAtDesc());
    }

    @GetMapping("/{id}")
    public ResponseEntity<PlatformTicket> getTicket(@PathVariable String id) {
        return ticketRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Data
    public static class TicketResponseRequest {
        private String response;
        private String status;
    }

    @PutMapping("/{id}")
    public ResponseEntity<PlatformTicket> updateTicket(@PathVariable String id, @RequestBody TicketResponseRequest request) {
        return ticketRepository.findById(id).map(ticket -> {
            if (request.getResponse() != null) {
                ticket.setResponse(request.getResponse());
            }
            if (request.getStatus() != null) {
                ticket.setStatus(request.getStatus());
            }
            return ResponseEntity.ok(ticketRepository.save(ticket));
        }).orElse(ResponseEntity.notFound().build());
    }

    @Data
    public static class MessageCreateRequest {
        private String message;
    }

    @GetMapping("/{id}/messages")
    public ResponseEntity<List<PlatformTicketMessage>> getTicketMessages(@PathVariable String id) {
        if (!ticketRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(messageRepository.findByTicketIdOrderByCreatedAtAsc(id));
    }

    @PostMapping("/{id}/messages")
    public ResponseEntity<PlatformTicketMessage> addTicketMessage(@PathVariable String id, @RequestBody MessageCreateRequest request) {
        if (!ticketRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }

        PlatformTicketMessage msg = new PlatformTicketMessage();
        msg.setTicketId(id);
        msg.setSenderType("PLATFORM");
        msg.setSenderEmail("admin@chatcrmlite.com"); // For now, hardcode admin email
        msg.setMessage(request.getMessage());

        return ResponseEntity.ok(messageRepository.save(msg));
    }
}
