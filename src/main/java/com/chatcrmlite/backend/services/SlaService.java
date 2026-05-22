package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.models.Ticket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class SlaService {
    private static final Logger log = LoggerFactory.getLogger(SlaService.class);

    public void calculateSlaDeadlines(Ticket ticket) {
        LocalDateTime now = LocalDateTime.now();

        switch (ticket.getPriority()) {
            case URGENT:
                ticket.setFirstResponseDueAt(now.plusMinutes(15));
                ticket.setResolutionDueAt(now.plusHours(4));
                break;
            case HIGH:
                ticket.setFirstResponseDueAt(now.plusHours(1));
                ticket.setResolutionDueAt(now.plusHours(8));
                break;
            case MEDIUM:
                ticket.setFirstResponseDueAt(now.plusHours(4));
                ticket.setResolutionDueAt(now.plusHours(24));
                break;
            case LOW:
                ticket.setFirstResponseDueAt(now.plusHours(8));
                ticket.setResolutionDueAt(now.plusHours(48));
                break;
        }

        log.debug("[SLA] Ticket {} - First response due: {}, Resolution due: {}",
                ticket.getId(), ticket.getFirstResponseDueAt(), ticket.getResolutionDueAt());
    }

    public boolean isSlaBreached(Ticket ticket) {
        LocalDateTime now = LocalDateTime.now();

        if (ticket.getFirstRespondedAt() == null && ticket.getFirstResponseDueAt() != null) {
            if (now.isAfter(ticket.getFirstResponseDueAt())) {
                return true;
            }
        }

        if (ticket.getResolvedAt() == null && ticket.getResolutionDueAt() != null) {
            if (now.isAfter(ticket.getResolutionDueAt())) {
                return true;
            }
        }

        return false;
    }

    public void markFirstResponse(Ticket ticket) {
        if (ticket.getFirstRespondedAt() == null) {
            ticket.setFirstRespondedAt(LocalDateTime.now());
            log.info("[SLA] First response recorded for ticket {}", ticket.getId());
        }
    }

    public String getSlaStatus(Ticket ticket) {
        if (ticket.isSlaBreached()) {
            return "BREACHED";
        }

        LocalDateTime now = LocalDateTime.now();

        if (ticket.getFirstRespondedAt() == null && ticket.getFirstResponseDueAt() != null) {
            long minutesLeft = java.time.Duration.between(now, ticket.getFirstResponseDueAt()).toMinutes();
            if (minutesLeft < 0) {
                return "BREACHED";
            } else if (minutesLeft < 30) {
                return "AT_RISK";
            }
        }

        if (ticket.getResolvedAt() == null && ticket.getResolutionDueAt() != null) {
            long hoursLeft = java.time.Duration.between(now, ticket.getResolutionDueAt()).toHours();
            if (hoursLeft < 0) {
                return "BREACHED";
            } else if (hoursLeft < 2) {
                return "AT_RISK";
            }
        }

        return "ON_TRACK";
    }
}
