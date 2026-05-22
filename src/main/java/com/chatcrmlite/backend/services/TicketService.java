package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.dto.*;
import com.chatcrmlite.backend.event.*;
import com.chatcrmlite.backend.models.*;
import com.chatcrmlite.backend.repositories.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TicketService {
    private static final Logger log = LoggerFactory.getLogger(TicketService.class);

    private static final String WEB_WAID_PREFIX = "web:";
    private static final int DUPLICATE_DETECTION_WINDOW_MINUTES = 10;

    @Autowired private TicketRepository ticketRepository;
    @Autowired private TicketCommentRepository commentRepository;
    @Autowired private TicketActivityRepository activityRepository;
    @Autowired private ContactRepository contactRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private TicketNumberGenerator ticketNumberGenerator;
    @Autowired private SlaService slaService;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private ReferenceNumberService referenceNumberService;

    @Transactional
    public Ticket submitSupportRequest(User owner, SupportRequest req) {
        String email = req.getEmail().trim().toLowerCase();
        String subject = req.getSubject().trim();

        LocalDateTime since = LocalDateTime.now().minusMinutes(DUPLICATE_DETECTION_WINDOW_MINUTES);
        List<Ticket> duplicates = ticketRepository.findPotentialDuplicates(owner, email, subject, since);
        if (!duplicates.isEmpty()) {
            log.warn("[TicketService] Duplicate ticket detected: email={} subject={}", email, subject);
            throw new DuplicateTicketException("A similar ticket was submitted recently. Please wait before resubmitting.");
        }

        String waId = WEB_WAID_PREFIX + email;
        Contact contact = contactRepository.findByWaIdAndOwner(waId, owner)
                .orElseGet(() -> {
                    Contact c = Contact.builder()
                            .waId(waId)
                            .email(email)
                            .name(req.getName().trim())
                            .source("support-form")
                            .owner(owner)
                            .build();
                    return contactRepository.save(c);
                });

        String ticketNumber = ticketNumberGenerator.generateTicketNumber(owner);
        String referenceNumber = referenceNumberService.generate(owner, ReferenceNumberService.EntityType.TICKET);

        Ticket ticket = Ticket.builder()
                .ticketNumber(ticketNumber)
                .referenceNumber(referenceNumber)
                .owner(owner)
                .contact(contact)
                .subject(subject)
                .description(req.getMessage().trim())
                .submitterName(req.getName().trim())
                .submitterEmail(email)
                .submitterPhone(req.getPhone())
                .category(req.getCategory())
                .source(Ticket.TicketSource.SUPPORT_FORM)
                .status(Ticket.TicketStatus.OPEN)
                .priority(Ticket.TicketPriority.MEDIUM)
                .build();

        slaService.calculateSlaDeadlines(ticket);
        Ticket saved = ticketRepository.save(ticket);
        logActivity(saved, null, TicketActivity.ActivityType.CREATED, null, null, "Ticket created from support form");
        eventPublisher.publishEvent(new TicketCreatedEvent(this, saved, "SUPPORT_FORM"));
        log.info("[TicketService] Ticket created: {} owner={}", ticketNumber, owner.getId());
        return saved;
    }

    @Transactional
    public Ticket createTicket(User owner, TicketRequest req) {
        Contact contact = null;
        if (req.getContactId() != null) {
            contact = contactRepository.findById(req.getContactId())
                    .filter(c -> c.getOwner().getId().equals(owner.getId()))
                    .orElseThrow(() -> new RuntimeException("Contact not found"));
        }

        User assignedTo = null;
        if (req.getAssignedToId() != null) {
            assignedTo = userRepository.findById(req.getAssignedToId())
                    .orElseThrow(() -> new RuntimeException("Agent not found"));
        }

        String ticketNumber = ticketNumberGenerator.generateTicketNumber(owner);
        String referenceNumber = referenceNumberService.generate(owner, ReferenceNumberService.EntityType.TICKET);

        Ticket ticket = Ticket.builder()
                .ticketNumber(ticketNumber)
                .referenceNumber(referenceNumber)
                .owner(owner)
                .contact(contact)
                .subject(req.getSubject().trim())
                .description(req.getDescription().trim())
                .submitterName(req.getSubmitterName())
                .submitterEmail(req.getSubmitterEmail())
                .submitterPhone(req.getSubmitterPhone())
                .priority(req.getPriority() != null ? req.getPriority() : Ticket.TicketPriority.MEDIUM)
                .category(req.getCategory())
                .assignedTo(assignedTo)
                .source(Ticket.TicketSource.MANUAL)
                .build();

        slaService.calculateSlaDeadlines(ticket);
        Ticket saved = ticketRepository.save(ticket);
        logActivity(saved, owner, TicketActivity.ActivityType.CREATED, null, null, "Ticket created manually");
        eventPublisher.publishEvent(new TicketCreatedEvent(this, saved, "MANUAL"));
        return saved;
    }

    @Transactional(readOnly = true)
    public Page<TicketDTO> getAllTickets(User owner, Pageable pageable) {
        return ticketRepository.findAllByOwnerActivePaged(owner, pageable).map(this::toDTO);
    }

    @Transactional(readOnly = true)
    public Page<TicketDTO> getTicketsByStatus(User owner, Ticket.TicketStatus status, Pageable pageable) {
        return ticketRepository.findAllByOwnerAndStatusPaged(owner, status, pageable).map(this::toDTO);
    }

    @Transactional(readOnly = true)
    public Page<TicketDTO> searchTickets(User owner, String query, Pageable pageable) {
        return ticketRepository.searchTickets(owner, query, pageable).map(this::toDTO);
    }

    @Transactional(readOnly = true)
    public TicketDTO getTicket(UUID id, User owner) {
        Ticket ticket = getOwnedTicket(id, owner);
        return toDTO(ticket);
    }

    @Transactional(readOnly = true)
    public TicketDTO getTicketByNumber(String ticketNumber, User owner) {
        Ticket ticket = ticketRepository.findByTicketNumber(ticketNumber)
                .filter(t -> t.getOwner().getId().equals(owner.getId()))
                .orElseThrow(() -> new RuntimeException("Ticket not found"));
        return toDTO(ticket);
    }

    @Transactional
    public TicketDTO updateStatus(UUID id, Ticket.TicketStatus newStatus, User owner) {
        Ticket ticket = getOwnedTicket(id, owner);
        Ticket.TicketStatus oldStatus = ticket.getStatus();
        ticket.setStatus(newStatus);
        if (newStatus == Ticket.TicketStatus.RESOLVED || newStatus == Ticket.TicketStatus.CLOSED) {
            ticket.setResolvedAt(LocalDateTime.now());
        }
        Ticket saved = ticketRepository.save(ticket);
        logActivity(saved, owner, TicketActivity.ActivityType.STATUS_CHANGED, oldStatus.name(), newStatus.name(), null);
        eventPublisher.publishEvent(new TicketStatusChangedEvent(this, saved, oldStatus, newStatus));
        return toDTO(saved);
    }

    @Transactional
    public TicketDTO updatePriority(UUID id, Ticket.TicketPriority newPriority, User owner) {
        Ticket ticket = getOwnedTicket(id, owner);
        Ticket.TicketPriority oldPriority = ticket.getPriority();
        ticket.setPriority(newPriority);
        slaService.calculateSlaDeadlines(ticket);
        Ticket saved = ticketRepository.save(ticket);
        logActivity(saved, owner, TicketActivity.ActivityType.PRIORITY_CHANGED, oldPriority.name(), newPriority.name(), null);
        return toDTO(saved);
    }

    @Transactional
    public TicketDTO assignTicket(UUID id, UUID agentId, User owner) {
        Ticket ticket = getOwnedTicket(id, owner);
        User agent = userRepository.findById(agentId).orElseThrow(() -> new RuntimeException("Agent not found"));
        String oldAgent = ticket.getAssignedTo() != null ? ticket.getAssignedTo().getDisplayName() : "Unassigned";
        ticket.setAssignedTo(agent);
        if (ticket.getStatus() == Ticket.TicketStatus.OPEN) {
            ticket.setStatus(Ticket.TicketStatus.IN_PROGRESS);
        }
        Ticket saved = ticketRepository.save(ticket);
        logActivity(saved, owner, TicketActivity.ActivityType.ASSIGNED, oldAgent, agent.getDisplayName(), null);
        eventPublisher.publishEvent(new TicketAssignedEvent(this, saved, agent));
        return toDTO(saved);
    }

    @Transactional
    public TicketDTO addComment(UUID id, User author, String message, boolean internal) {
        Ticket ticket = getOwnedTicket(id, author);
        TicketComment comment = TicketComment.builder()
                .ticket(ticket)
                .author(author)
                .authorName(author.getDisplayName() != null ? author.getDisplayName() : author.getEmail())
                .authorType(TicketComment.AuthorType.AGENT)
                .message(message.trim())
                .internal(internal)
                .build();
        commentRepository.save(comment);
        slaService.markFirstResponse(ticket);
        ticketRepository.save(ticket);
        logActivity(ticket, author, TicketActivity.ActivityType.COMMENT_ADDED, null, null, internal ? "Internal comment added" : "Comment added");
        // Only notify the customer for public (non-internal) comments
        if (!internal) {
            eventPublisher.publishEvent(new TicketCommentAddedEvent(this, ticket, message.trim(),
                    author.getDisplayName() != null ? author.getDisplayName() : author.getEmail()));
        }
        return toDTO(ticket);
    }

    @Transactional
    public void deleteTicket(UUID id, User owner) {
        Ticket ticket = getOwnedTicket(id, owner);
        ticket.setDeleted(true);
        ticket.setDeletedAt(LocalDateTime.now());
        ticket.setDeletedBy(owner.getId());
        ticketRepository.save(ticket);
        logActivity(ticket, owner, TicketActivity.ActivityType.DELETED, null, null, null);
        log.info("[TicketService] Ticket soft-deleted: {}", ticket.getTicketNumber());
    }

    @Transactional(readOnly = true)
    public long countOpenTickets(User owner) {
        return ticketRepository.countByOwnerAndStatusAndDeletedFalse(owner, Ticket.TicketStatus.OPEN);
    }

    @Transactional(readOnly = true)
    public List<TicketActivity> getTicketActivities(UUID ticketId, User owner) {
        Ticket ticket = getOwnedTicket(ticketId, owner);
        return activityRepository.findAllByTicketOrderByCreatedAtDesc(ticket);
    }

    @Transactional
    public void checkSlaBreaches(User owner) {
        List<Ticket> candidates = ticketRepository.findSlaBreachCandidates(owner, LocalDateTime.now());
        for (Ticket ticket : candidates) {
            if (slaService.isSlaBreached(ticket)) {
                ticket.setSlaBreached(true);
                ticketRepository.save(ticket);
                logActivity(ticket, null, TicketActivity.ActivityType.SLA_BREACHED, null, null, "SLA deadline missed");
                log.warn("[SLA] Breach detected: {}", ticket.getTicketNumber());
            }
        }
    }

    private Ticket getOwnedTicket(UUID id, User owner) {
        return ticketRepository.findByIdActive(id)
                .filter(t -> t.getOwner().getId().equals(owner.getId()))
                .orElseThrow(() -> new RuntimeException("Ticket not found"));
    }

    private void logActivity(Ticket ticket, User user, TicketActivity.ActivityType type, String oldValue, String newValue, String details) {
        TicketActivity activity = TicketActivity.builder()
                .ticket(ticket)
                .user(user)
                .userName(user != null ? (user.getDisplayName() != null ? user.getDisplayName() : user.getEmail()) : "System")
                .activityType(type)
                .oldValue(oldValue)
                .newValue(newValue)
                .details(details)
                .build();
        activityRepository.save(activity);
    }

    public TicketDTO toDTO(Ticket t) {
        try {
            // Load comments for the ticket
            List<TicketComment> comments = commentRepository.findAllByTicketActive(t);
            List<TicketCommentDTO> commentDTOs = comments.stream()
                    .map(c -> TicketCommentDTO.builder()
                            .id(c.getId().toString())
                            .authorName(c.getAuthorName())
                            .authorRole(c.getAuthorType().name())
                            .message(c.getMessage())
                            .createdAt(c.getCreatedAt().toString())
                            .build())
                    .collect(Collectors.toList());

            boolean isNew = t.getCreatedAt() != null && ChronoUnit.HOURS.between(t.getCreatedAt(), LocalDateTime.now()) < 24;
            String slaStatus = "OK"; // Simplified for now
            try {
                slaStatus = slaService.getSlaStatus(t);
            } catch (Exception e) {
                log.warn("Error getting SLA status for ticket {}: {}", t.getId(), e.getMessage());
            }

            return TicketDTO.builder()
                    .id(t.getId())
                    .ticketNumber(t.getTicketNumber())
                    .contactId(t.getContact() != null ? t.getContact().getId() : null)
                    .contactName(t.getContact() != null ? t.getContact().getName() : null)
                    .contactWaId(t.getContact() != null ? t.getContact().getWaId() : null)
                    .submitterName(t.getSubmitterName())
                    .submitterEmail(t.getSubmitterEmail())
                    .submitterPhone(t.getSubmitterPhone())
                    .subject(t.getSubject())
                    .description(t.getDescription())
                    .status(t.getStatus())
                    .priority(t.getPriority())
                    .source(t.getSource())
                    .category(t.getCategory())
                    .assignedToId(t.getAssignedTo() != null ? t.getAssignedTo().getId() : null)
                    .assignedToName(t.getAssignedTo() != null ? t.getAssignedTo().getDisplayName() : null)
                    .comments(commentDTOs)
                    .slaStatus(slaStatus)
                    .slaBreached(t.isSlaBreached())
                    .firstResponseDueAt(t.getFirstResponseDueAt())
                    .resolutionDueAt(t.getResolutionDueAt())
                    .firstRespondedAt(t.getFirstRespondedAt())
                    .createdAt(t.getCreatedAt())
                    .updatedAt(t.getUpdatedAt())
                    .resolvedAt(t.getResolvedAt())
                    .isNew(isNew)
                    .createdAtHuman(humanTime(t.getCreatedAt()))
                    .build();
        } catch (Exception e) {
            log.error("Error converting ticket to DTO: ", e);
            // Return minimal DTO to avoid complete failure
            return TicketDTO.builder()
                    .id(t.getId())
                    .ticketNumber(t.getTicketNumber())
                    .subject(t.getSubject())
                    .description(t.getDescription())
                    .status(t.getStatus())
                    .priority(t.getPriority())
                    .source(t.getSource())
                    .slaStatus("ERROR")
                    .slaBreached(false)
                    .isNew(false)
                    .createdAtHuman("Unknown")
                    .build();
        }
    }

    private String humanTime(LocalDateTime dt) {
        if (dt == null) return "";
        long minutes = ChronoUnit.MINUTES.between(dt, LocalDateTime.now());
        if (minutes < 1) return "Just now";
        if (minutes < 60) return minutes + " min" + (minutes == 1 ? "" : "s") + " ago";
        long hours = ChronoUnit.HOURS.between(dt, LocalDateTime.now());
        if (hours < 24) return hours + " hour" + (hours == 1 ? "" : "s") + " ago";
        long days = ChronoUnit.DAYS.between(dt, LocalDateTime.now());
        if (days == 1) return "Yesterday";
        return days + " days ago";
    }

    public static class DuplicateTicketException extends RuntimeException {
        public DuplicateTicketException(String message) {
            super(message);
        }
    }
}
