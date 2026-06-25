package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.dto.AppointmentRequest;
import com.chatcrmlite.backend.event.AppointmentScheduledEvent;
import com.chatcrmlite.backend.models.Appointment;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.AppointmentRepository;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AppointmentService {
    private static final Logger log = LoggerFactory.getLogger(AppointmentService.class);

    @Autowired private AppointmentRepository appointmentRepository;
    @Autowired private ContactRepository contactRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private ReferenceNumberService referenceNumberService;
    @Autowired private com.chatcrmlite.backend.services.tenant.QuotaEnforcerService quotaEnforcerService;

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
    public Appointment bookAppointment(AppointmentRequest req, User owner) {
        Contact contact = contactRepository.findById(req.getContactId())
                .filter(c -> c.getOwner().getTenant().getId().equals(owner.getTenant().getId()))
                .orElseThrow(() -> new RuntimeException("Contact not found or access denied"));

        quotaEnforcerService.verifyBookingQuota(owner.getTenant().getId());
        String referenceNumber = referenceNumberService.generate(owner, ReferenceNumberService.EntityType.APPOINTMENT);
        Appointment appt = Appointment.builder()
                .referenceNumber(referenceNumber)
                .contact(contact)
                .owner(owner)
                .appointmentDateTime(req.getAppointmentDateTime())
                .title(req.getTitle())
                .meetingLink(req.getMeetingLink())
                .collectedData("{}")
                .build();

        Appointment saved = appointmentRepository.save(appt);
        eventPublisher.publishEvent(new AppointmentScheduledEvent(this, saved, "MANUAL"));
        return saved;
    }

    @Transactional
    public Appointment bookFromFlow(Contact contact, User owner, String title,
                                    Map<String, String> flowData, LocalDateTime dateTime) {
        quotaEnforcerService.verifyBookingQuota(owner.getTenant().getId());
        String referenceNumber = referenceNumberService.generate(owner, ReferenceNumberService.EntityType.APPOINTMENT);
        Appointment appt = Appointment.builder()
                .referenceNumber(referenceNumber)
                .contact(contact)
                .owner(owner)
                .title(title)
                .appointmentDateTime(dateTime)
                .collectedData(serialize(flowData))
                .build();
        Appointment saved = appointmentRepository.save(appt);
        eventPublisher.publishEvent(new AppointmentScheduledEvent(this, saved, "FLOW"));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Appointment> getAllAppointments(User owner) {
        if (isAdmin(owner)) {
            return appointmentRepository.findAllOrderByAppointmentDateTimeAsc();
        }
        return appointmentRepository.findByOwner_IdOrderByAppointmentDateTimeAsc(owner.getId());
    }

    @Transactional(readOnly = true)
    public List<Appointment> getAppointmentsForContact(UUID contactId, User owner) {
        if (isAdmin(owner)) {
            return appointmentRepository.findByContact_IdOrderByAppointmentDateTimeAsc(contactId);
        }
        return appointmentRepository.findByContact_IdAndOwner_IdOrderByAppointmentDateTimeAsc(contactId, owner.getId());
    }

    @Transactional(readOnly = true)
    public List<Appointment> getTodayAppointments(User owner) {
        LocalDateTime start = LocalDate.now().atStartOfDay();
        LocalDateTime end   = start.plusDays(1).minusSeconds(1);
        if (isAdmin(owner)) {
            return appointmentRepository.findByAppointmentDateTimeBetweenAndStatus(
                    start, end, Appointment.AppointmentStatus.SCHEDULED);
        }
        return appointmentRepository.findByOwner_IdAndAppointmentDateTimeBetweenAndStatus(
                owner.getId(), start, end, Appointment.AppointmentStatus.SCHEDULED);
    }

    @Transactional(readOnly = true)
    public long countTodayAppointments(User owner) {
        LocalDateTime start = LocalDate.now().atStartOfDay();
        LocalDateTime end   = start.plusDays(1).minusSeconds(1);
        if (isAdmin(owner)) {
            return appointmentRepository.countByAppointmentDateTimeBetweenAndStatus(
                    start, end, Appointment.AppointmentStatus.SCHEDULED);
        }
        return appointmentRepository.countByOwner_IdAndAppointmentDateTimeBetweenAndStatus(
                owner.getId(), start, end, Appointment.AppointmentStatus.SCHEDULED);
    }

    @Transactional
    public Appointment completeAppointment(UUID id, User owner) {
        Appointment appt = getOwned(id, owner);
        appt.setStatus(Appointment.AppointmentStatus.COMPLETED);
        Appointment saved = appointmentRepository.save(appt);
        eventPublisher.publishEvent(new AppointmentScheduledEvent(this, saved, "MANUAL"));
        return saved;
    }

    @Transactional
    public Appointment cancelAppointment(UUID id, User owner) {
        Appointment appt = getOwned(id, owner);
        appt.setStatus(Appointment.AppointmentStatus.CANCELLED);
        Appointment saved = appointmentRepository.save(appt);
        eventPublisher.publishEvent(new AppointmentScheduledEvent(this, saved, "MANUAL"));
        return saved;
    }

    @Transactional
    public Appointment markNoShow(UUID id, User owner) {
        Appointment appt = getOwned(id, owner);
        appt.setStatus(Appointment.AppointmentStatus.NO_SHOW);
        Appointment saved = appointmentRepository.save(appt);
        eventPublisher.publishEvent(new AppointmentScheduledEvent(this, saved, "MANUAL"));
        return saved;
    }

    private Appointment getOwned(UUID id, User owner) {
        return appointmentRepository.findByIdWithContact(id)
                .filter(a -> a.getOwner().getTenant().getId().equals(owner.getTenant().getId()))
                .orElseThrow(() -> new RuntimeException("Appointment not found or access denied"));
    }
}
