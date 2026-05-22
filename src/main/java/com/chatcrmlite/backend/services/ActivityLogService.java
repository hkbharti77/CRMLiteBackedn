package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.dto.ActivityLogDTO;
import com.chatcrmlite.backend.models.*;
import com.chatcrmlite.backend.repositories.ActivityLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Unified CRM Activity Logging Service.
 *
 * Writes append-only records to the activity_logs table on behalf of
 * all domain modules (Lead, Booking, Appointment).
 *
 * Called by the ActivityLogListener (event-driven) so that individual
 * domain services do NOT need a direct dependency on this class.
 *
 * All log methods are @Async so they never block the main transaction.
 */
@Slf4j
@Service
public class ActivityLogService {
    private final ActivityLogRepository activityLogRepository;
    private final ObjectMapper objectMapper;

    public ActivityLogService(ActivityLogRepository activityLogRepository, ObjectMapper objectMapper) {
        this.activityLogRepository = activityLogRepository;
        this.objectMapper = objectMapper;
    }

    // ── Queries ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ActivityLogDTO> getContactTimeline(Contact contact) {
        List<ActivityLog> logs = activityLogRepository.findByContactOrderByCreatedAtDesc(contact);
        return logs.stream()
                .map(ActivityLogDTO::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<ActivityLogDTO> getOwnerFeed(User owner) {
        List<ActivityLog> logs = activityLogRepository.findByOwnerOrderByCreatedAtDesc(owner);
        return logs.stream()
                .map(ActivityLogDTO::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<ActivityLogDTO> getEntityHistory(String entityType, UUID entityId) {
        List<ActivityLog> logs = activityLogRepository.findByEntity(entityType, entityId);
        return logs.stream()
                .map(ActivityLogDTO::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<ActivityLogDTO> getRecentContactActivity(UUID contactId, int limit) {
        List<ActivityLog> logs = activityLogRepository.findRecentByContactId(contactId, limit);
        return logs.stream()
                .map(ActivityLogDTO::fromEntity)
                .collect(Collectors.toList());
    }

    // ── Writers ────────────────────────────────────────────────────────────

    /**
     * Log a Lead creation event.
     */
    @Async
    @Transactional
    public void logLeadCreated(Lead lead, String source) {
        String summary = String.format("Lead created via %s (status: %s)", source, lead.getStatus());
        writeLog(
            lead.getOwner(), lead.getContact(),
            ActivityLog.TYPE_LEAD, lead.getId(),
            ActivityLog.LEAD_CREATED, source, summary,
            toJson(Map.of("status", String.valueOf(lead.getStatus()), "source", source))
        );
    }

    /**
     * Log a Lead status change event.
     */
    @Async
    @Transactional
    public void logLeadStatusChanged(Lead lead, String oldStatus, String source) {
        String summary = String.format("Lead status changed from %s → %s", oldStatus, lead.getStatus());
        writeLog(
            lead.getOwner(), lead.getContact(),
            ActivityLog.TYPE_LEAD, lead.getId(),
            ActivityLog.LEAD_STATUS_CHANGED, source, summary,
            toJson(Map.of("oldStatus", oldStatus, "newStatus", String.valueOf(lead.getStatus())))
        );
    }

    /**
     * Log a Booking confirmed event.
     */
    @Async
    @Transactional
    public void logBookingConfirmed(Booking booking, String source) {
        String summary = String.format("Booking confirmed for '%s' (slot: %s)",
                booking.getService(),
                booking.getPreferredSlot() != null ? booking.getPreferredSlot() : "TBD");
        writeLog(
            booking.getOwner(), booking.getContact(),
            ActivityLog.TYPE_BOOKING, booking.getId(),
            ActivityLog.BOOKING_CONFIRMED, source, summary,
            toJson(Map.of("service", booking.getService(),
                          "slot", booking.getPreferredSlot() != null ? booking.getPreferredSlot() : "TBD",
                          "status", String.valueOf(booking.getStatus())))
        );
    }

    /**
     * Log a Booking status change (cancelled, completed, no-show).
     */
    @Async
    @Transactional
    public void logBookingStatusChanged(Booking booking, String activityType, String source) {
        String summary = String.format("Booking for '%s' marked as %s", booking.getService(), activityType);
        writeLog(
            booking.getOwner(), booking.getContact(),
            ActivityLog.TYPE_BOOKING, booking.getId(),
            activityType, source, summary,
            toJson(Map.of("service", booking.getService(), "newStatus", String.valueOf(booking.getStatus())))
        );
    }

    /**
     * Log an Appointment scheduled event.
     */
    @Async
    @Transactional
    public void logAppointmentScheduled(Appointment appointment, String source) {
        String summary = String.format("Appointment scheduled: '%s' at %s",
                appointment.getTitle(), appointment.getAppointmentDateTime());
        writeLog(
            appointment.getOwner(), appointment.getContact(),
            ActivityLog.TYPE_APPOINTMENT, appointment.getId(),
            ActivityLog.APPOINTMENT_SCHEDULED, source, summary,
            toJson(Map.of("title", appointment.getTitle(),
                          "dateTime", appointment.getAppointmentDateTime().toString(),
                          "status", String.valueOf(appointment.getStatus())))
        );
    }

    /**
     * Log an Appointment status change (cancelled, completed, no-show).
     */
    @Async
    @Transactional
    public void logAppointmentStatusChanged(Appointment appointment, String activityType, String source) {
        String summary = String.format("Appointment '%s' marked as %s", appointment.getTitle(), activityType);
        writeLog(
            appointment.getOwner(), appointment.getContact(),
            ActivityLog.TYPE_APPOINTMENT, appointment.getId(),
            activityType, source, summary,
            toJson(Map.of("title", appointment.getTitle(), "newStatus", String.valueOf(appointment.getStatus())))
        );
    }

    // ── Internal ───────────────────────────────────────────────────────────

    private void writeLog(User owner, Contact contact,
                          String entityType, UUID entityId,
                          String activityType, String source,
                          String summary, String payload) {
        try {
            ActivityLog activityLog = ActivityLog.builder()
                    .owner(owner)
                    .contact(contact)
                    .entityType(entityType)
                    .entityId(entityId)
                    .activityType(activityType)
                    .source(source)
                    .summary(summary)
                    .payload(payload)
                    .build();
            activityLogRepository.save(activityLog);
        } catch (Exception ex) {
            // Never let logging break the main business flow
            log.warn("[ActivityLog] Failed to write log for entity {}:{} — {}", entityType, entityId, ex.getMessage());
        }
    }

    private String toJson(Map<String, String> data) {
        try { return objectMapper.writeValueAsString(data); }
        catch (Exception e) { return "{}"; }
    }
}
