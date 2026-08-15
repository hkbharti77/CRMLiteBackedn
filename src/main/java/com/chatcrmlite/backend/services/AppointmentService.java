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
    @Autowired private com.chatcrmlite.backend.services.FlowConfigService flowConfigService;
    @Autowired private com.chatcrmlite.backend.services.GoogleCalendarService googleCalendarService;

    @Autowired(required = false) private com.chatcrmlite.backend.repositories.WhatsAppConfigRepository whatsAppConfigRepository;
    @Autowired(required = false) private com.chatcrmlite.backend.clients.WhatsAppClient whatsAppClient;
    @Autowired(required = false) private com.chatcrmlite.backend.repositories.TenantRepository tenantRepository;

    private boolean isAdmin(User user) {
        return user.getRole() == User.Role.ADMIN || user.getRole() == User.Role.OWNER || user.getRole() == User.Role.AGENT;
    }

    private UUID resolveTenantId(User owner) {
        if (owner == null) return null;
        if (owner.getTenant() != null) {
            return owner.getTenant().getId();
        }
        if (tenantRepository != null && owner.getId() != null) {
            try {
                java.util.Optional<com.chatcrmlite.backend.models.Tenant> tenantOpt = tenantRepository.findById(owner.getId());
                if (tenantOpt.isPresent()) {
                    return tenantOpt.get().getId();
                }
            } catch (Exception ignored) {}
        }
        return owner.getId();
    }

    public Map<String, String> parseCollectedData(String json) {
        try {
            if (json == null || json.isBlank()) return new HashMap<>();
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse collectedData JSON: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    private String serialize(Map<String, String> data) {
        try {
            return objectMapper.writeValueAsString(data != null ? data : new HashMap<>());
        } catch (Exception e) {
            return "{}";
        }
    }

    @Transactional
    public Appointment bookAppointment(AppointmentRequest req, User owner) {
        Contact contact = null;
        UUID tenantId = resolveTenantId(owner);
        if (req.getContactId() != null) {
            contact = contactRepository.findById(req.getContactId())
                    .filter(c -> {
                        UUID cTenantId = c.getTenant() != null ? c.getTenant().getId() : null;
                        return cTenantId == null || cTenantId.equals(tenantId);
                    })
                    .orElseThrow(() -> new RuntimeException("Contact not found or access denied"));
        }

        if (tenantId != null) {
            try {
                quotaEnforcerService.verifyBookingQuota(tenantId);
            } catch (Exception e) {
                log.warn("[AppointmentService] Quota check skipped/warned for tenantId={}: {}", tenantId, e.getMessage());
            }
        }
        String referenceNumber = referenceNumberService.generate(owner, ReferenceNumberService.EntityType.APPOINTMENT);

        String meetingLink = req.getMeetingLink();
        String clientEmail = req.getClientEmail();
        if (clientEmail == null || clientEmail.isBlank()) {
            clientEmail = contact != null ? contact.getEmail() : null;
        }

        int durationMinutes = req.getDurationMinutes() != null ? req.getDurationMinutes() : 60;
        String googleEventId = null;

        if (Boolean.TRUE.equals(req.getGenerateMeetLink())) {
            if (owner.getGoogleAccessToken() == null || owner.getGoogleAccessToken().isBlank()) {
                throw new IllegalStateException("Google Calendar is not connected. Please link your Google account in Settings.");
            }
            try {
                String[] meetRes = googleCalendarService.createMeetLink(
                        owner,
                        req.getTitle(),
                        req.getAppointmentDateTime(),
                        clientEmail,
                        durationMinutes
                );
                meetingLink = meetRes[0];
                googleEventId = meetRes[1];
            } catch (Exception e) {
                log.error("[AppointmentService] Failed to auto-create Meet link during manual booking", e);
                throw new RuntimeException("Google Meet link generation failed: " + e.getMessage(), e);
            }
        }

        Map<String, String> data = new HashMap<>();
        if (req.getClientEmail() != null && !req.getClientEmail().isBlank()) {
            data.put("email", req.getClientEmail());
        }
        if (googleEventId != null) {
            data.put("googleEventId", googleEventId);
        }
        String collectedData = data.isEmpty() ? "{}" : serialize(data);

        Appointment appt = Appointment.builder()
                .referenceNumber(referenceNumber)
                .contact(contact)
                .owner(owner)
                .appointmentDateTime(req.getAppointmentDateTime())
                .title(req.getTitle())
                .meetingLink(meetingLink)
                .collectedData(collectedData)
                .source(req.getSource() != null ? req.getSource() : "MANUAL")
                .build();

        Appointment saved = appointmentRepository.save(appt);
        eventPublisher.publishEvent(new AppointmentScheduledEvent(this, saved, "MANUAL"));
        return saved;
    }

    @Transactional
    public Appointment bookFromFlow(Contact contact, User owner, String title,
                                    Map<String, String> flowData, LocalDateTime dateTime, String source) {
        UUID tenantId = resolveTenantId(owner);
        if (tenantId != null) {
            try {
                quotaEnforcerService.verifyBookingQuota(tenantId);
            } catch (Exception e) {
                log.warn("[AppointmentService] Quota check skipped/warned for tenantId={}: {}", tenantId, e.getMessage());
            }
        }
        String referenceNumber = null;
        try {
            referenceNumber = referenceNumberService.generate(owner, ReferenceNumberService.EntityType.APPOINTMENT);
        } catch (Exception e) {
            log.warn("[AppointmentService] Failed to generate reference number: {}", e.getMessage());
            referenceNumber = "APT-" + System.currentTimeMillis();
        }
        
        // Fetch dynamic labels to replace raw keys (e.g. custom_text_1 -> Are you currently using CRM?)
        Map<String, String> resolvedData = new HashMap<>();
        if (flowData != null) {
            try {
                List<com.chatcrmlite.backend.dto.flow.FlowFieldConfig> configs = 
                        flowConfigService.getConfigurableFields(owner, "appointment");
                Map<String, String> keyToLabel = new HashMap<>();
                if (configs != null) {
                    for (com.chatcrmlite.backend.dto.flow.FlowFieldConfig cfg : configs) {
                        if (cfg.getLabel() != null && !cfg.getLabel().isBlank()) {
                            keyToLabel.put(cfg.getKey(), cfg.getLabel());
                        }
                    }
                }
                java.util.List<String> fixedKeys = java.util.List.of("name", "email", "phone", "date", "time", "preferred_date", "preferred_time", "title");
                for (Map.Entry<String, String> entry : flowData.entrySet()) {
                    if (fixedKeys.contains(entry.getKey())) continue;
                    String displayLabel = keyToLabel.getOrDefault(entry.getKey(), entry.getKey());
                    resolvedData.put(displayLabel, entry.getValue());
                }
            } catch (Exception e) {
                java.util.List<String> fixedKeys = java.util.List.of("name", "email", "phone", "date", "time", "preferred_date", "preferred_time", "title");
                for (Map.Entry<String, String> entry : flowData.entrySet()) {
                    if (!fixedKeys.contains(entry.getKey())) {
                        resolvedData.put(entry.getKey(), entry.getValue());
                    }
                }
            }
        }

        Appointment appt = Appointment.builder()
                .referenceNumber(referenceNumber)
                .contact(contact)
                .owner(owner)
                .title(title != null ? title : "Appointment")
                .appointmentDateTime(dateTime != null ? dateTime : LocalDateTime.now().plusDays(1).withHour(10).withMinute(0))
                .collectedData(serialize(resolvedData))
                .source(source != null ? source : "MANUAL")
                .build();
        Appointment saved = appointmentRepository.save(appt);
        try {
            eventPublisher.publishEvent(new AppointmentScheduledEvent(this, saved, "FLOW"));
        } catch (Exception e) {
            log.warn("[AppointmentService] Failed to publish AppointmentScheduledEvent: {}", e.getMessage());
        }
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Appointment> getAllAppointments(User owner) {
        UUID tenantId = resolveTenantId(owner);
        if (tenantId != null) {
            return appointmentRepository.findAllByTenantIdOrderByAppointmentDateTimeAsc(tenantId);
        }
        return appointmentRepository.findByOwner_IdOrderByAppointmentDateTimeAsc(owner.getId());
    }

    @Transactional(readOnly = true)
    public List<Appointment> getAppointmentsForContact(UUID contactId, User owner) {
        UUID tenantId = resolveTenantId(owner);
        if (tenantId != null) {
            return appointmentRepository.findByContactIdAndTenantIdOrderByAppointmentDateTimeAsc(contactId, tenantId);
        }
        return appointmentRepository.findByContact_IdAndOwner_IdOrderByAppointmentDateTimeAsc(contactId, owner.getId());
    }

    @Transactional(readOnly = true)
    public List<Appointment> getUpcomingAppointments(User owner) {
        // Next 7 days (excludes today — already shown separately)
        LocalDateTime startOfTomorrow = LocalDate.now().plusDays(1).atStartOfDay();
        LocalDateTime endOfWeek = LocalDate.now().plusDays(7).atTime(23, 59, 59);
        UUID tenantId = resolveTenantId(owner);
        if (tenantId != null) {
            return appointmentRepository.findByTenantIdAndAppointmentDateTimeBetweenAndStatus(
                    tenantId, startOfTomorrow, endOfWeek, Appointment.AppointmentStatus.SCHEDULED);
        }
        return appointmentRepository.findByOwner_IdAndAppointmentDateTimeBetweenAndStatus(
                owner.getId(), startOfTomorrow, endOfWeek, Appointment.AppointmentStatus.SCHEDULED);
    }

    private java.time.ZoneId getTenantZoneId(User owner) {
        String tz = (owner != null && owner.getTenant() != null && owner.getTenant().getTimezone() != null)
                ? owner.getTenant().getTimezone()
                : "Asia/Kolkata";
        try {
            return java.time.ZoneId.of(tz);
        } catch (Exception e) {
            return java.time.ZoneId.of("Asia/Kolkata");
        }
    }

    @Transactional(readOnly = true)
    public List<Appointment> getTodayAppointments(User owner) {
        java.time.ZoneId zoneId = getTenantZoneId(owner);
        LocalDateTime start = java.time.LocalDate.now(zoneId).atStartOfDay();
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
        java.time.ZoneId zoneId = getTenantZoneId(owner);
        LocalDateTime start = java.time.LocalDate.now(zoneId).atStartOfDay();
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
        deleteGoogleEvent(appt, owner);
        Appointment saved = appointmentRepository.save(appt);
        eventPublisher.publishEvent(new AppointmentScheduledEvent(this, saved, "MANUAL"));
        return saved;
    }

    @Transactional
    public Appointment cancelAppointment(UUID id, User owner) {
        Appointment appt = getOwned(id, owner);
        appt.setStatus(Appointment.AppointmentStatus.CANCELLED);
        deleteGoogleEvent(appt, owner);
        Appointment saved = appointmentRepository.save(appt);
        eventPublisher.publishEvent(new AppointmentScheduledEvent(this, saved, "MANUAL"));
        return saved;
    }

    private void deleteGoogleEvent(Appointment appt, User owner) {
        if (appt.getCollectedData() != null) {
            Map<String, String> data = parseCollectedData(appt.getCollectedData());
            String eventId = data.get("googleEventId");
            if (eventId != null && !eventId.isBlank()) {
                googleCalendarService.deleteEvent(owner, eventId);
                data.remove("googleEventId");
                appt.setCollectedData(serialize(data));
            }
        }
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

    /**
     * Generates a Google Meet link for an appointment via GoogleCalendarService,
     * persists it to the appointment, and re-publishes the scheduled event so the
     * email listener sends the updated link to the client.
     */
    @Transactional
    public String generateAndSaveMeetLink(UUID appointmentId, User owner,
                                          com.chatcrmlite.backend.services.GoogleCalendarService googleCalendarService, Integer durationMinutes) {
        Appointment appt = getOwned(appointmentId, owner);

        // Resolve the client email for the Google Calendar invite
        String clientEmail = null;
        if (appt.getContact() != null) {
            clientEmail = appt.getContact().getEmail();
        }
        if ((clientEmail == null || clientEmail.isBlank()) && appt.getCollectedData() != null) {
            Map<String, String> data = parseCollectedData(appt.getCollectedData());
            clientEmail = data.get("email");
        }

        String meetLink;
        try {
            String[] meetRes = googleCalendarService.createMeetLink(
                    owner,
                    appt.getTitle() != null ? appt.getTitle() : "Appointment",
                    appt.getAppointmentDateTime(),
                    clientEmail,
                    durationMinutes != null ? durationMinutes : 60);
            meetLink = meetRes[0];
            String eventId = meetRes[1];
            
            Map<String, String> data = parseCollectedData(appt.getCollectedData());
            data.put("googleEventId", eventId);
            appt.setCollectedData(serialize(data));
            
        } catch (Exception e) {
            log.error("[AppointmentService] Failed to create Meet link for appt={}", appointmentId, e);
            throw new RuntimeException(e.getMessage(), e);
        }

        appt.setMeetingLink(meetLink);
        Appointment saved = appointmentRepository.save(appt);

        // Send WhatsApp notification if contact has WhatsApp ID
        if (saved.getContact() != null && saved.getContact().getWaId() != null && whatsAppConfigRepository != null && whatsAppClient != null) {
            try {
                UUID tenantId = owner.getTenant().getId();
                whatsAppConfigRepository.findByTenantId(tenantId).ifPresent(config -> {
                    if (config.getAccessToken() != null && config.getPhoneNumberId() != null) {
                        String waMsg = "📹 *Google Meet Link for your appointment* (" + saved.getTitle() + "):\n" + meetLink;
                        whatsAppClient.sendMessage(saved.getContact().getWaId(), waMsg, config.getAccessToken(), config.getPhoneNumberId());
                        log.info("[AppointmentService] Sent Google Meet link via WhatsApp to {}", saved.getContact().getWaId());
                    }
                });
            } catch (Exception e) {
                log.warn("[AppointmentService] Failed to send Meet link via WhatsApp: {}", e.getMessage());
            }
        }

        // Re-publish event so the email listener sends the Meet link to the client
        eventPublisher.publishEvent(new com.chatcrmlite.backend.event.AppointmentScheduledEvent(this, saved, "MEET_LINK_GENERATED"));
        return meetLink;
    }
}
