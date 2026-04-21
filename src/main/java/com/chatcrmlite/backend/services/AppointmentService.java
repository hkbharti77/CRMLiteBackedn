package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.dto.AppointmentRequest;
import com.chatcrmlite.backend.models.Appointment;
import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.AppointmentRepository;
import com.chatcrmlite.backend.repositories.LeadRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired private AppointmentRepository appointmentRepository;
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
    public Appointment bookAppointment(AppointmentRequest req, User owner) {
        Lead lead = leadRepository.findById(req.getLeadId())
                .filter(l -> l.getOwner().getId().equals(owner.getId()))
                .orElseThrow(() -> new RuntimeException("Lead not found or access denied"));

        lead.setStatus(Lead.LeadStatus.BOOKED);
        lead.setLastActivity(LocalDateTime.now());
        leadRepository.save(lead);

        Appointment appt = Appointment.builder()
                .lead(lead)
                .owner(owner)
                .appointmentDateTime(req.getAppointmentDateTime())
                .title(req.getTitle())
                .meetingLink(req.getMeetingLink())
                .collectedData("{}")
                .build();

        return appointmentRepository.save(appt);
    }

    /**
     * Called from WhatsAppFlowService when APPOINTMENT flow completes.
     * Stores full structured flow data as JSON in collectedData.
     */
    @Transactional
    public Appointment bookFromFlow(Lead lead, User owner, String title,
                                    Map<String, String> flowData, LocalDateTime dateTime) {
        Appointment appt = Appointment.builder()
                .lead(lead)
                .owner(owner)
                .title(title)
                .appointmentDateTime(dateTime)
                .collectedData(serialize(flowData))
                .build();
        return appointmentRepository.save(appt);
    }

    public List<Appointment> getAllAppointments(User owner) {
        return appointmentRepository.findByOwner_IdOrderByAppointmentDateTimeAsc(owner.getId());
    }

    public List<Appointment> getAppointmentsForLead(UUID leadId, User owner) {
        return appointmentRepository
                .findByLead_IdAndOwner_IdOrderByAppointmentDateTimeAsc(leadId, owner.getId());
    }

    public List<Appointment> getTodayAppointments(User owner) {
        LocalDateTime start = LocalDate.now().atStartOfDay();
        LocalDateTime end   = start.plusDays(1).minusSeconds(1);
        return appointmentRepository.findByOwner_IdAndAppointmentDateTimeBetweenAndStatus(
                owner.getId(), start, end, Appointment.AppointmentStatus.SCHEDULED);
    }

    public long countTodayAppointments(User owner) {
        LocalDateTime start = LocalDate.now().atStartOfDay();
        LocalDateTime end   = start.plusDays(1).minusSeconds(1);
        return appointmentRepository.countByOwner_IdAndAppointmentDateTimeBetweenAndStatus(
                owner.getId(), start, end, Appointment.AppointmentStatus.SCHEDULED);
    }

    @Transactional
    public Appointment completeAppointment(UUID id, User owner) {
        Appointment appt = getOwned(id, owner);
        appt.setStatus(Appointment.AppointmentStatus.COMPLETED);
        return appointmentRepository.save(appt);
    }

    @Transactional
    public Appointment cancelAppointment(UUID id, User owner) {
        Appointment appt = getOwned(id, owner);
        appt.setStatus(Appointment.AppointmentStatus.CANCELLED);
        return appointmentRepository.save(appt);
    }

    @Transactional
    public Appointment markNoShow(UUID id, User owner) {
        Appointment appt = getOwned(id, owner);
        appt.setStatus(Appointment.AppointmentStatus.NO_SHOW);
        return appointmentRepository.save(appt);
    }

    private Appointment getOwned(UUID id, User owner) {
        return appointmentRepository.findById(id)
                .filter(a -> a.getOwner().getId().equals(owner.getId()))
                .orElseThrow(() -> new RuntimeException("Appointment not found or access denied"));
    }
}
