package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.event.LeadCreatedEvent;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.LeadRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.chatcrmlite.backend.services.lead.LeadEnquiryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Service
public class PublicSubmissionService {
    private static final Logger log = LoggerFactory.getLogger(PublicSubmissionService.class);

    private static final int MAX_VALUE_LENGTH = 500;
    private static final String WEB_WAID_PREFIX = "web:";

    @Autowired
    private ContactRepository contactRepository;
    @Autowired
    private LeadRepository leadRepository;
    @Autowired
    private AppointmentService appointmentService;
    @Autowired
    private BookingService bookingService;
    @Autowired
    private LeadEnquiryService leadEnquiryService;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ApplicationEventPublisher eventPublisher;
    @Autowired
    private com.chatcrmlite.backend.services.tenant.QuotaEnforcerService quotaEnforcerService;
    @Autowired
    private ReferenceNumberService referenceNumberService;

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
        if (title == null)
            title = "Web Appointment";

        LocalDateTime apptTime = com.chatcrmlite.backend.util.DateTimeParser.extractAndParse(clean);

        appointmentService.bookFromFlow(contact, owner, title, clean, apptTime, "WEB_BOT");
        log.info("[PublicSubmission] Appointment created for contact={} owner={}", contact.getId(), owner.getId());
    }

    @Transactional
    public void submitBooking(User owner, Map<String, String> data) {
        Map<String, String> clean = sanitize(data);
        Contact contact = findOrCreateContact(owner, clean);

        String service = firstNonBlank(clean, "service", "occasion", "shoot_type", "class_type", "goal");
        if (service == null)
            service = "Web Booking";

        String preferredSlot = firstNonBlank(clean, "date_time", "preferred_slot", "event_date");

        bookingService.bookFromFlow(contact, owner, service, preferredSlot, clean, "WEB_BOT");
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
        if (data == null)
            return new HashMap<>();
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
        // Verify lead quota
        quotaEnforcerService.verifyLeadQuota(owner.getTenant().getId());

        String leadNumber = referenceNumberService.generate(owner, ReferenceNumberService.EntityType.LEAD);

        Lead lead = Lead.builder()
                .leadNumber(leadNumber)
                .contact(contact)
                .owner(owner)
                .status(status)
                .build();

        Lead savedLead = leadRepository.save(lead);
        
        leadEnquiryService.appendEnquiry(savedLead, "Lead submitted via Web Widget.", "WEB_WIDGET", "web-widget", data);

        return savedLead;
    }
    private String firstNonBlank(Map<String, String> data, String... keys) {
        for (String key : keys) {
            String val = data.get(key);
            if (val != null && !val.isBlank()) return val;
        }
        return null;
    }

    private LocalDateTime parseDateTime(String raw) {
        return com.chatcrmlite.backend.util.DateTimeParser.parse(raw);
    }
}
