package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.dto.BookingRequest;
import com.chatcrmlite.backend.event.BookingConfirmedEvent;
import com.chatcrmlite.backend.models.Booking;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.BookingRepository;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {
    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    @Autowired private BookingRepository bookingRepository;
    @Autowired private ContactRepository contactRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private ReferenceNumberService referenceNumberService;
    @Autowired private com.chatcrmlite.backend.services.tenant.QuotaEnforcerService quotaEnforcerService;
    @Autowired private com.chatcrmlite.backend.services.FlowConfigService flowConfigService;

    private boolean isAdmin(User user) {
        return user.getRole() == User.Role.ADMIN || user.getRole() == User.Role.OWNER || user.getRole() == User.Role.AGENT;
    }

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

    @Transactional
    public Booking createBooking(BookingRequest req, User owner) {
        Contact contact = contactRepository.findById(req.getContactId())
                .filter(c -> c.getOwner().getTenant().getId().equals(owner.getTenant().getId()))
                .orElseThrow(() -> new RuntimeException("Contact not found or access denied"));

        quotaEnforcerService.verifyBookingQuota(owner.getTenant().getId());
        String referenceNumber = referenceNumberService.generate(owner, ReferenceNumberService.EntityType.BOOKING);
        Booking booking = Booking.builder()
                .referenceNumber(referenceNumber)
                .contact(contact)
                .owner(owner)
                .service(req.getService())
                .preferredSlot(req.getPreferredSlot())
                .collectedData("{}")
                .source(req.getSource() != null ? req.getSource() : "MANUAL")
                .build();

        Booking saved = bookingRepository.save(booking);
        eventPublisher.publishEvent(new BookingConfirmedEvent(this, saved, "MANUAL"));
        return saved;
    }

    @Transactional
    public Booking bookFromFlow(Contact contact, User owner, String service,
                                String preferredSlot, Map<String, String> flowData, String source) {
        quotaEnforcerService.verifyBookingQuota(owner.getTenant().getId());
        String referenceNumber = referenceNumberService.generate(owner, ReferenceNumberService.EntityType.BOOKING);
        
        // Fetch dynamic labels to replace raw keys
        Map<String, String> resolvedData = new HashMap<>();
        try {
            List<com.chatcrmlite.backend.dto.flow.FlowFieldConfig> configs = 
                    flowConfigService.getConfigurableFields(owner, "booking");
            Map<String, String> keyToLabel = new HashMap<>();
            for (com.chatcrmlite.backend.dto.flow.FlowFieldConfig cfg : configs) {
                if (cfg.getLabel() != null && !cfg.getLabel().isBlank()) {
                    keyToLabel.put(cfg.getKey(), cfg.getLabel());
                }
            }
            java.util.List<String> fixedKeys = java.util.List.of("name", "email", "phone", "service", "preferredSlot", "preferred_slot");
            for (Map.Entry<String, String> entry : flowData.entrySet()) {
                if (fixedKeys.contains(entry.getKey())) continue;
                String displayLabel = keyToLabel.getOrDefault(entry.getKey(), entry.getKey());
                resolvedData.put(displayLabel, entry.getValue());
            }
        } catch (Exception e) {
            java.util.List<String> fixedKeys = java.util.List.of("name", "email", "phone", "service", "preferredSlot", "preferred_slot");
            for (Map.Entry<String, String> entry : flowData.entrySet()) {
                if (!fixedKeys.contains(entry.getKey())) {
                    resolvedData.put(entry.getKey(), entry.getValue());
                }
            }
        }

        Booking booking = Booking.builder()
                .referenceNumber(referenceNumber)
                .contact(contact)
                .owner(owner)
                .service(service)
                .preferredSlot(preferredSlot)
                .collectedData(serialize(resolvedData))
                .source(source != null ? source : "MANUAL")
                .build();
        Booking saved = bookingRepository.save(booking);
        eventPublisher.publishEvent(new BookingConfirmedEvent(this, saved, "FLOW"));
        return saved;
    }

    private UUID resolveTenantId(User owner) {
        if (owner == null) return null;
        if (owner.getTenant() != null) return owner.getTenant().getId();
        return owner.getId();
    }

    @Transactional(readOnly = true)
    public List<Booking> getAllBookings(User owner) {
        UUID tenantId = resolveTenantId(owner);
        if (tenantId != null) {
            return bookingRepository.findAllByTenantIdOrderByCreatedAtDesc(tenantId);
        }
        return bookingRepository.findByOwner_IdOrderByCreatedAtDesc(owner.getId());
    }

    @Transactional(readOnly = true)
    public List<Booking> getBookingsForContact(UUID contactId, User owner) {
        UUID tenantId = resolveTenantId(owner);
        if (tenantId != null) {
            return bookingRepository.findByContactIdAndTenantIdOrderByCreatedAtDesc(contactId, tenantId);
        }
        return bookingRepository.findByContact_IdAndOwner_IdOrderByCreatedAtDesc(contactId, owner.getId());
    }

    @Transactional(readOnly = true)
    public List<Booking> getBookingsByStatus(Booking.BookingStatus status, User owner) {
        UUID tenantId = resolveTenantId(owner);
        if (tenantId != null) {
            return bookingRepository.findByTenantIdAndStatus(tenantId, status);
        }
        return bookingRepository.findByOwner_IdAndStatus(owner.getId(), status);
    }

    @Transactional
    public Booking completeBooking(UUID id, User owner) {
        Booking b = getOwned(id, owner);
        b.setStatus(Booking.BookingStatus.COMPLETED);
        Booking saved = bookingRepository.save(b);
        eventPublisher.publishEvent(new BookingConfirmedEvent(this, saved, "MANUAL"));
        return saved;
    }

    @Transactional
    public Booking cancelBooking(UUID id, User owner) {
        Booking b = getOwned(id, owner);
        b.setStatus(Booking.BookingStatus.CANCELLED);
        Booking saved = bookingRepository.save(b);
        eventPublisher.publishEvent(new BookingConfirmedEvent(this, saved, "MANUAL"));
        return saved;
    }

    @Transactional
    public Booking markNoShow(UUID id, User owner) {
        Booking b = getOwned(id, owner);
        b.setStatus(Booking.BookingStatus.NO_SHOW);
        Booking saved = bookingRepository.save(b);
        eventPublisher.publishEvent(new BookingConfirmedEvent(this, saved, "MANUAL"));
        return saved;
    }

    private Booking getOwned(UUID id, User owner) {
        UUID tenantId = resolveTenantId(owner);
        return bookingRepository.findByIdWithContact(id)
                .filter(b -> b.getOwner() != null && b.getOwner().getTenant() != null && b.getOwner().getTenant().getId().equals(tenantId))
                .orElseThrow(() -> new RuntimeException("Booking not found or access denied"));
    }
}
