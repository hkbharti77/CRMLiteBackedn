package com.chatcrmlite.backend.controllers.platform;

import com.chatcrmlite.backend.models.PlatformTicket;
import com.chatcrmlite.backend.models.PlatformTicketMessage;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.repositories.PlatformTicketRepository;
import com.chatcrmlite.backend.repositories.PlatformTicketMessageRepository;
import com.chatcrmlite.backend.repositories.TenantRepository;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/platform/tickets")
public class PlatformTicketController {

    @Autowired
    private PlatformTicketRepository ticketRepository;

    @Autowired
    private PlatformTicketMessageRepository messageRepository;

    @Autowired
    private TenantRepository tenantRepository;

    @Data
    public static class PlatformTicketDto {
        private String id;
        private String tenantId;
        private String tenantName;
        private String title;
        private String subject;
        private String description;
        private String status;
        private String priority = "MEDIUM";
        private String category = "Support";
        private String submittedByEmail;
        private String createdByEmail;
        private String response;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private List<PlatformTicketMessage> messages;
    }

    private PlatformTicketDto toDto(PlatformTicket t) {
        PlatformTicketDto dto = new PlatformTicketDto();
        dto.setId(t.getId());
        dto.setTenantId(t.getTenantId());
        dto.setTitle(t.getTitle() != null ? t.getTitle() : "Support Ticket");
        dto.setSubject(dto.getTitle());
        dto.setDescription(t.getDescription() != null ? t.getDescription() : "");
        dto.setStatus(t.getStatus() != null ? t.getStatus().toUpperCase() : "OPEN");
        dto.setSubmittedByEmail(t.getSubmittedByEmail() != null ? t.getSubmittedByEmail() : "support@customer.com");
        dto.setCreatedByEmail(dto.getSubmittedByEmail());
        dto.setResponse(t.getResponse());
        dto.setCreatedAt(t.getCreatedAt());
        dto.setUpdatedAt(t.getUpdatedAt());

        // Resolve Tenant Name
        if (t.getTenantId() != null) {
            try {
                UUID tid = UUID.fromString(t.getTenantId());
                tenantRepository.findById(tid).ifPresent(tenant -> dto.setTenantName(tenant.getBusinessName()));
            } catch (Exception ignored) {
                dto.setTenantName("Tenant " + (t.getTenantId().length() > 8 ? t.getTenantId().substring(0, 8) : t.getTenantId()));
            }
        }
        if (dto.getTenantName() == null) {
            dto.setTenantName("Business Tenant");
        }
        return dto;
    }

    @GetMapping
    public ResponseEntity<List<PlatformTicketDto>> getAllTickets() {
        List<PlatformTicket> list = ticketRepository.findAllByOrderByCreatedAtDesc();
        List<PlatformTicketDto> dtos = list.stream().map(this::toDto).collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/{id}")
    public ResponseEntity<PlatformTicketDto> getTicket(@PathVariable String id) {
        return ticketRepository.findById(id).map(ticket -> {
            PlatformTicketDto dto = toDto(ticket);
            dto.setMessages(messageRepository.findByTicketIdOrderByCreatedAtAsc(id));
            return ResponseEntity.ok(dto);
        }).orElse(ResponseEntity.notFound().build());
    }

    @Data
    public static class TicketResponseRequest {
        private String response;
        private String status;
        private String priority;
    }

    @PutMapping("/{id}")
    public ResponseEntity<PlatformTicketDto> updateTicket(@PathVariable String id, @RequestBody TicketResponseRequest request) {
        return ticketRepository.findById(id).map(ticket -> {
            if (request.getResponse() != null) {
                ticket.setResponse(request.getResponse());
            }
            if (request.getStatus() != null) {
                ticket.setStatus(request.getStatus().toUpperCase());
            }
            PlatformTicket saved = ticketRepository.save(ticket);
            return ResponseEntity.ok(toDto(saved));
        }).orElse(ResponseEntity.notFound().build());
    }

    @Data
    public static class MessageCreateRequest {
        private String message;
        private String body;
        private String senderEmail;
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
        msg.setSenderEmail(request.getSenderEmail() != null ? request.getSenderEmail() : "admin@gyanvaniai.online");
        msg.setMessage(request.getMessage() != null ? request.getMessage() : (request.getBody() != null ? request.getBody() : ""));

        return ResponseEntity.ok(messageRepository.save(msg));
    }
}
