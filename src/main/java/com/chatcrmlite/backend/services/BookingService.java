package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.dto.BookingRequest;
import com.chatcrmlite.backend.models.Booking;
import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.BookingRepository;
import com.chatcrmlite.backend.repositories.LeadRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {

    @Autowired private BookingRepository bookingRepository;
    @Autowired private LeadRepository leadRepository;
    @Autowired private ObjectMapper objectMapper;

    // ── Helpers ────────────────────────────────────────────────────────────

    public Map<String, String> parseCollectedData(String json) {
        try {
            if (json == null || json.isBlank()) return new HashMap<>();
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private String serialize(Map<String, String> data) {
        try { return objectMapper.writeValueAsString(data); }
        catch (Exception e) { return "{}"; }
    }

    // ── CRUD ───────────────────────────────────────────────────────────────

    @Transactional
    public Booking createBooking(BookingRequest req, User owner) {
        Lead lead = leadRepository.findById(req.getLeadId())
                .filter(l -> l.getOwner().getId().equals(owner.getId()))
                .orElseThrow(() -> new RuntimeException("Lead not found or access denied"));

        lead.setStatus(Lead.LeadStatus.BOOKED);
        lead.setLastActivity(LocalDateTime.now());
        leadRepository.save(lead);

        Booking booking = Booking.builder()
                .lead(lead)
                .owner(owner)
                .service(req.getService())
                .preferredSlot(req.getPreferredSlot())
                .collectedData("{}")
                .build();

        return bookingRepository.save(booking);
    }

    /**
     * Called from WhatsAppFlowService when BOOKING flow completes.
     */
    @Transactional
    public Booking bookFromFlow(Lead lead, User owner, String service,
                                String preferredSlot, Map<String, String> flowData) {
        Booking booking = Booking.builder()
                .lead(lead)
                .owner(owner)
                .service(service)
                .preferredSlot(preferredSlot)
                .collectedData(serialize(flowData))
                .build();
        return bookingRepository.save(booking);
    }

    public List<Booking> getAllBookings(User owner) {
        return bookingRepository.findByOwner_IdOrderByCreatedAtDesc(owner.getId());
    }

    public List<Booking> getBookingsForLead(UUID leadId, User owner) {
        return bookingRepository.findByLead_IdAndOwner_IdOrderByCreatedAtDesc(leadId, owner.getId());
    }

    public List<Booking> getBookingsByStatus(Booking.BookingStatus status, User owner) {
        return bookingRepository.findByOwner_IdAndStatus(owner.getId(), status);
    }

    @Transactional
    public Booking completeBooking(UUID id, User owner) {
        Booking b = getOwned(id, owner);
        b.setStatus(Booking.BookingStatus.COMPLETED);
        return bookingRepository.save(b);
    }

    @Transactional
    public Booking cancelBooking(UUID id, User owner) {
        Booking b = getOwned(id, owner);
        b.setStatus(Booking.BookingStatus.CANCELLED);
        return bookingRepository.save(b);
    }

    @Transactional
    public Booking markNoShow(UUID id, User owner) {
        Booking b = getOwned(id, owner);
        b.setStatus(Booking.BookingStatus.NO_SHOW);
        return bookingRepository.save(b);
    }

    private Booking getOwned(UUID id, User owner) {
        return bookingRepository.findById(id)
                .filter(b -> b.getOwner().getId().equals(owner.getId()))
                .orElseThrow(() -> new RuntimeException("Booking not found or access denied"));
    }
}
