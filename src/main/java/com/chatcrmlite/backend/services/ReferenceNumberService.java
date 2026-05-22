package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.AppointmentRepository;
import com.chatcrmlite.backend.repositories.BookingRepository;
import com.chatcrmlite.backend.repositories.LeadRepository;
import com.chatcrmlite.backend.repositories.TicketRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Central service for generating human-readable reference numbers across all entity types.
 *
 * Format:  {PREFIX}-{TYPE}-{YYYYMMDD}-{NNNN}
 *
 * Examples:
 *   GYAN-L-20250520-0001   ← Lead
 *   GYAN-A-20250520-0001   ← Appointment
 *   GYAN-B-20250520-0001   ← Booking
 *   GYAN-T-20250520-0001   ← Ticket
 *
 * PREFIX uniqueness:
 *   - First 4 alphanumeric chars of business name (uppercase)
 *   - If another tenant already uses the same 4-char prefix,
 *     we append the first 2 chars of the tenant UUID to make it unique.
 *     e.g. "GYAN" → "GYAN3F" (6 chars)
 *
 * Serial number:
 *   - Per-owner, per-type, per-date count (resets each day)
 *   - 4-digit zero-padded
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReferenceNumberService {

    public enum EntityType {
        LEAD("L"),
        APPOINTMENT("A"),
        BOOKING("B"),
        TICKET("T");

        private final String code;
        EntityType(String code) { this.code = code; }
        public String getCode() { return code; }
    }

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final UserRepository userRepository;
    private final LeadRepository leadRepository;
    private final AppointmentRepository appointmentRepository;
    private final BookingRepository bookingRepository;
    private final TicketRepository ticketRepository;

    /**
     * Generate a reference number for the given owner and entity type.
     * Thread-safe: uses DB count as sequence source.
     */
    public synchronized String generate(User owner, EntityType type) {
        String prefix = resolveUniquePrefix(owner);
        String date   = LocalDate.now().format(DATE_FMT);
        long   seq    = countTodayForType(owner, type) + 1;
        String ref    = String.format("%s-%s-%s-%04d", prefix, type.getCode(), date, seq);
        log.debug("[RefNum] Generated {} for owner={}", ref, owner.getId());
        return ref;
    }

    // ── Prefix resolution ──────────────────────────────────────────────────

    /**
     * Returns a globally unique 4–6 char prefix for this tenant.
     *
     * Algorithm:
     *   1. Take first 4 alphanumeric chars of business name → "GYAN"
     *   2. Check if any OTHER tenant uses the same 4-char prefix
     *   3. If collision → append first 2 chars of tenant UUID → "GYAN3F"
     *   4. If business name has < 4 chars → pad with "X"
     */
    private String resolveUniquePrefix(User owner) {
        String bizName = owner.getBusinessName();
        String base;

        if (bizName != null && !bizName.isBlank()) {
            base = bizName.toUpperCase()
                    .replaceAll("[^A-Z0-9]", "");
            // Pad with X if too short
            while (base.length() < 4) base += "X";
            base = base.substring(0, 4);
        } else {
            // Fallback: use first 4 chars of tenant UUID
            base = owner.getId().toString()
                    .toUpperCase()
                    .replaceAll("-", "")
                    .substring(0, 4);
        }

        // Check for collision with other tenants
        boolean collision = userRepository.existsByBusinessNamePrefixAndNotId(base, owner.getId());
        if (collision) {
            // Append first 2 hex chars of tenant UUID to disambiguate
            String uuidSuffix = owner.getId().toString()
                    .toUpperCase()
                    .replaceAll("-", "")
                    .substring(0, 2);
            return base + uuidSuffix;  // e.g. "GYAN3F"
        }

        return base;  // e.g. "GYAN"
    }

    // ── Per-day sequence counts ────────────────────────────────────────────

    private long countTodayForType(User owner, EntityType type) {
        LocalDate today = LocalDate.now();
        String dateStr = today.format(DATE_FMT);

        return switch (type) {
            case LEAD        -> leadRepository.countByOwnerAndDatePrefix(owner.getId(), dateStr);
            case APPOINTMENT -> appointmentRepository.countByOwnerAndDatePrefix(owner.getId(), dateStr);
            case BOOKING     -> bookingRepository.countByOwnerAndDatePrefix(owner.getId(), dateStr);
            case TICKET      -> ticketRepository.countByOwnerAndDatePrefix(owner.getId(), dateStr);
        };
    }
}
