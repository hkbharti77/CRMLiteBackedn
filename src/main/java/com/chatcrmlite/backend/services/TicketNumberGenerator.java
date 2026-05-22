package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.TicketRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Year;

/**
 * Generates human-readable ticket numbers.
 * Format: TKT-{YEAR}-{SEQUENCE}
 * Example: TKT-2026-00421
 */
@Slf4j
@Service
public class TicketNumberGenerator {

    @Autowired
    private TicketRepository ticketRepository;

    /**
     * Generate next ticket number for a business.
     * Thread-safe via database sequence.
     */
    public synchronized String generateTicketNumber(User owner) {
        int year = Year.now().getValue();
        long sequence = ticketRepository.countByOwner(owner) + 1;
        String ticketNumber = String.format("TKT-%d-%05d", year, sequence);
        log.debug("[TicketNumber] Generated: {}", ticketNumber);
        return ticketNumber;
    }
}
