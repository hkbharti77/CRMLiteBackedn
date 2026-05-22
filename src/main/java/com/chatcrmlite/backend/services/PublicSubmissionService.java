package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.dto.EnquiryDTO;
import com.chatcrmlite.backend.event.LeadCreatedEvent;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.LeadRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PublicSubmissionService {
    private static final Logger log = LoggerFactory.getLogger(PublicSubmissionService.class);

    private static final int MAX_VALUE_LENGTH = 500;
    private static final String WEB_WAID_PREFIX = "web:";

    @Autowired private ContactRepository contactRepository;
    @Autowired private LeadRepository leadRepository;
    @Autowired private AppointmentService appointmentService;
    @Autowired private BookingService bookingService;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ApplicationEventPublisher eventPublisher;

    @Transactional
    public void submitLead(User owner, Map<String, String> data) {
        Map<String, String> clean = sanitize(data);
        Contact contact = findOrCreateContact(owner, clean);
        Lead lead = createLeadRecord(contact, owner, clean, Lead.LeadStatus.NEW);
        eventPublisher.publishEvent(new LeadCreatedEvent(this, lead, "WEB_WIDGET"));
        log.info("[PublicSubmission] Lead created for contact={} owner={}", contact.getId(), owner.getId());
    }

    @Transactional
    public void submitEnquiry(User owner, Map<String, String> data) {
        Map<String, String> clean = sanitize(data);
        Contact contact = findOrCreateContact(owner, clean);
        Lead lead = createLeadRecord(contact, owner, clean, Lead.LeadStatus.FOLLOW_UP);
        eventPublisher.publishEvent(new LeadCreatedEvent(this, lead, "WEB_WIDGET"));
        log.info("[PublicSubmission] Enquiry created for contact={} owner={}", contact.getId(), owner.getId());
    }

    @Transactional
    public void submitAppointment(User owner, Map<String, String> data) {
        Map<String, String> clean = sanitize(data);
        Contact contact = findOrCreateContact(owner, clean);

        String title = firstNonBlank(clean, "treatment", "service", "consultation_type");
        if (title == null) title = "Web Appointment";

        appointmentService.bookFromFlow(contact, owner, title, clean, null);
        log.info("[PublicSubmission] Appointment created for contact={} owner={}", contact.getId(), owner.getId());
    }

    @Transactional
    public void submitBooking(User owner, Map<String, String> data) {
        Map<String, String> clean = sanitize(data);
        Contact contact = findOrCreateContact(owner, clean);

        String service = firstNonBlank(clean, "service", "occasion", "shoot_type", "class_type", "goal");
        if (service == null) service = "Web Booking";

        String preferredSlot = firstNonBlank(clean, "date_time", "preferred_slot", "event_date");

        bookingService.bookFromFlow(contact, owner, service, preferredSlot, clean);
        log.info("[PublicSubmission] Booking created for contact={} owner={}", contact.getId(), owner.getId());
    }

    Contact findOrCreateContact(User owner, Map<String, String> data) {
        String email = data.get("email");
        String waId = WEB_WAID_PREFIX + email;

        return contactRepository.findByWaIdAndOwner(waId, owner)
                .orElseGet(() -> {
                    String name = firstNonBlank(data,
                            "name", "patient_name", "client_name",
                            "student_name", "visitor_name");

                    Contact contact = Contact.builder()
                            .waId(waId)
                            .email(email)
                            .name(name != null ? name : email)
                            .source("web-widget")
                            .owner(owner)
                            .build();

                    Contact saved = contactRepository.save(contact);
                    log.info("[PublicSubmission] New web contact created: id={} waId={}", saved.getId(), waId);
                    return saved;
                });
    }

    Map<String, String> sanitize(Map<String, String> data) {
        if (data == null) return new HashMap<>();
        Map<String, String> result = new HashMap<>(data.size());
        for (Map.Entry<String, String> entry : data.entrySet()) {
            String value = entry.getValue();
            if (value != null && value.length() > MAX_VALUE_LENGTH) {
                value = value.substring(0, MAX_VALUE_LENGTH);
            }
            result.put(entry.getKey(), value);
        }
        return result;
    }

    private Lead createLeadRecord(Contact contact, User owner,
                                   Map<String, String> data, Lead.LeadStatus status) {
        StringBuilder summary = new StringBuilder("Web widget submission:\n");
        data.forEach((k, v) -> {
            if (v != null && !v.isBlank()) {
                summary.append("• ").append(k).append(": ").append(v).append("\n");
            }
        });

        EnquiryDTO enquiry = EnquiryDTO.builder()
                .id(UUID.randomUUID().toString())
                .type("WEB_WIDGET")
                .message(summary.toString().trim())
                .source("web-widget")
                .status("OPEN")
                .createdAt(LocalDateTime.now().toString())
                .build();

        String enquiriesJson;
        try {
            List<EnquiryDTO> list = new ArrayList<>();
            list.add(enquiry);
            enquiriesJson = objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            enquiriesJson = "[]";
        }

        Lead lead = Lead.builder()
                .contact(contact)
                .owner(owner)
                .status(status)
                .enquiries(enquiriesJson)
                .build();

        return leadRepository.save(lead);
    }

    private String firstNonBlank(Map<String, String> data, String... keys) {
        for (String key : keys) {
            String val = data.get(key);
            if (val != null && !val.isBlank()) return val;
        }
        return null;
    }
}
