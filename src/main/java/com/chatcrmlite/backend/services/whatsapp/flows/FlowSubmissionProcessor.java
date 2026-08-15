package com.chatcrmlite.backend.services.whatsapp.flows;

import com.chatcrmlite.backend.event.LeadCreatedEvent;
import com.chatcrmlite.backend.event.TicketCreatedEvent;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.Ticket;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.flows.FlowCategory;
import com.chatcrmlite.backend.models.flows.FlowSubmission;
import com.chatcrmlite.backend.models.flows.SubmissionProcessingStatus;
import com.chatcrmlite.backend.models.flows.WhatsAppFlow;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.LeadRepository;
import com.chatcrmlite.backend.repositories.TicketRepository;
import com.chatcrmlite.backend.repositories.flows.FlowSubmissionRepository;
import com.chatcrmlite.backend.services.ReferenceNumberService;
import com.chatcrmlite.backend.services.SlaService;
import com.chatcrmlite.backend.services.TicketNumberGenerator;
import com.chatcrmlite.backend.services.lead.LeadService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.services.websocket.DistributedWebSocketPublisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class FlowSubmissionProcessor {

    private final FlowSubmissionRepository submissionRepository;
    private final LeadRepository leadRepository;
    private final ContactRepository contactRepository;
    private final TicketRepository ticketRepository;
    private final FlowConfirmationService confirmationService;
    private final LeadService leadService;
    private final TicketNumberGenerator ticketNumberGenerator;
    private final ReferenceNumberService referenceNumberService;
    private final SlaService slaService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final com.chatcrmlite.backend.repositories.UserRepository userRepository;

    @Autowired(required = false)
    private DistributedWebSocketPublisher webSocketPublisher;

    @Autowired(required = false)
    private CacheManager cacheManager;

    public FlowSubmissionProcessor(FlowSubmissionRepository submissionRepository,
                                   LeadRepository leadRepository,
                                   ContactRepository contactRepository,
                                   TicketRepository ticketRepository,
                                   FlowConfirmationService confirmationService,
                                   LeadService leadService,
                                   TicketNumberGenerator ticketNumberGenerator,
                                   ReferenceNumberService referenceNumberService,
                                   SlaService slaService,
                                   ApplicationEventPublisher eventPublisher,
                                   ObjectMapper objectMapper,
                                   com.chatcrmlite.backend.repositories.UserRepository userRepository) {
        this.submissionRepository = submissionRepository;
        this.leadRepository = leadRepository;
        this.contactRepository = contactRepository;
        this.ticketRepository = ticketRepository;
        this.confirmationService = confirmationService;
        this.leadService = leadService;
        this.ticketNumberGenerator = ticketNumberGenerator;
        this.referenceNumberService = referenceNumberService;
        this.slaService = slaService;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
        this.userRepository = userRepository;
    }

    private User resolveOwner(FlowSubmission submission, Contact contact) {
        if (contact != null && contact.getOwner() != null) return contact.getOwner();
        Tenant targetTenant = resolveTenant(submission, contact, null);
        if (targetTenant != null && userRepository != null) {
            java.util.List<User> users = userRepository.findAllByTenant(targetTenant);
            if (!users.isEmpty()) {
                return users.stream()
                        .filter(u -> u.getRole() == User.Role.OWNER || u.getRole() == User.Role.ADMIN)
                        .findFirst()
                        .orElse(users.get(0));
            }
        }
        return null;
    }

    private Tenant resolveTenant(FlowSubmission submission, Contact contact, User owner) {
        if (contact != null && contact.getTenant() != null) return contact.getTenant();
        if (owner != null && owner.getTenant() != null) return owner.getTenant();
        if (submission.getTenant() != null) return submission.getTenant();
        if (submission.getFlow() != null && submission.getFlow().getTenant() != null) return submission.getFlow().getTenant();
        return null;
    }

    private void evictLeadCaches() {
        if (cacheManager != null) {
            for (String cacheName : java.util.List.of("leadsByContact", "latestLeadByContact", "activeLeadCount", "leadsByUser", "leadsByStatus", "revenueReport")) {
                var cache = cacheManager.getCache(cacheName);
                if (cache != null) {
                    try {
                        cache.clear();
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    @Transactional
    public void processSubmission(FlowSubmission submission) {
        if (submission == null) return;
        submission.setProcessingStatus(SubmissionProcessingStatus.PROCESSING);
        submissionRepository.save(submission);

        try {
            Contact contact = submission.getContact();
            WhatsAppFlow flow = submission.getFlow();
            FlowCategory category = (flow != null && flow.getCategory() != null) ? flow.getCategory() : FlowCategory.LEAD_GENERATION;

            // 1. Parse raw submitted JSON data
            Map<String, Object> rawDataMap = new HashMap<>();
            if (submission.getRawResponseJson() != null && !submission.getRawResponseJson().isBlank()) {
                try {
                    rawDataMap = objectMapper.readValue(submission.getRawResponseJson(), new TypeReference<>() {});
                } catch (Exception ex) {
                    log.warn("⚠️ [FlowProcessor] Failed to parse rawResponseJson: {}", ex.getMessage());
                }
            }

            // 2. Canonical Normalization: Maps any custom Meta Flow field names into standard CRM keys
            Map<String, String> normalizedMap = normalizeFlowSubmissionData(rawDataMap);
            submission.setNormalizedDataJson(objectMapper.writeValueAsString(normalizedMap));

            // 3. Auto-update Contact name and email if provided in form
            if (contact != null) {
                String submittedName = normalizedMap.get("name");
                if (submittedName != null && !submittedName.isBlank()) {
                    contact.setName(submittedName.trim());
                }

                String submittedEmail = normalizedMap.get("email");
                if (submittedEmail != null && !submittedEmail.isBlank()) {
                    contact.setEmail(submittedEmail.trim());
                }

                contactRepository.save(contact);
            }

            // 4. Category-Specific Processing
            switch (category) {
                case APPOINTMENT_BOOKING -> processAppointmentBooking(submission, contact, normalizedMap, rawDataMap);
                case LEAD_GENERATION -> processLeadGeneration(submission, contact, normalizedMap, rawDataMap);
                case CUSTOMER_SUPPORT -> processCustomerSupport(submission, contact, normalizedMap, rawDataMap);
                case SURVEY, OTHER -> processGeneralInquiry(submission, contact, normalizedMap, rawDataMap);
            }

            // 5. Send Automated Contextual Confirmation via WhatsApp
            confirmationService.sendConfirmation(submission);

            // 6. Mark Complete
            submission.setProcessingStatus(SubmissionProcessingStatus.PROCESSED);
            submission.setProcessedAt(LocalDateTime.now());
            submission.setProcessingError(null);
            submissionRepository.save(submission);

            log.info("🎉 [FlowProcessor] Successfully processed FlowSubmission {} for category {} (Contact: {}, Name: {}, Email: {})", 
                    submission.getId(), category, contact != null ? contact.getWaId() : "N/A", normalizedMap.get("name"), normalizedMap.get("email"));

        } catch (Exception e) {
            log.error("❌ [FlowProcessor] Failed to process FlowSubmission {}: {}", submission.getId(), e.getMessage(), e);
            submission.setProcessingStatus(SubmissionProcessingStatus.PROCESSING_FAILED);
            submission.setProcessingError(e.getMessage());
            submissionRepository.save(submission);
        }
    }

    private void processAppointmentBooking(FlowSubmission submission, Contact contact, Map<String, String> normalizedMap, Map<String, Object> rawData) {
        if (contact == null) return;
        User owner = resolveOwner(submission, contact);
        Tenant tenant = resolveTenant(submission, contact, owner);

        if (contact != null) {
            boolean contactUpdated = false;
            if (contact.getOwner() == null && owner != null) {
                contact.setOwner(owner);
                contactUpdated = true;
            }
            if (contact.getTenant() == null && tenant != null) {
                contact.setTenant(tenant);
                contactUpdated = true;
            }
            if (contactUpdated) contactRepository.save(contact);
        }

        String service = normalizedMap.getOrDefault("serviceCategory", "Appointment");
        String date = normalizedMap.getOrDefault("preferred_date", "");
        String time = normalizedMap.getOrDefault("time_slot", "");
        String flowDisplayName = cleanFlowDisplayName(submission.getFlow(), "Appointment Booking");
        String dealLabel = flowDisplayName + " - " + service + (!date.isBlank() ? " (" + date + (!time.isBlank() ? " " + time : "") + ")" : "");

        String leadNumber = referenceNumberService.generate(owner, ReferenceNumberService.EntityType.LEAD);

        Optional<Lead> existingLead = leadRepository.findTopByContactOrderByCreatedAtDesc(contact);
        Lead lead = existingLead.orElseGet(() -> {
            Lead newLead = Lead.builder()
                    .contact(contact)
                    .status(Lead.LeadStatus.BOOKED)
                    .owner(owner)
                    .dealLabel(dealLabel)
                    .leadNumber(leadNumber)
                    .build();
            if (tenant != null) newLead.setTenant(tenant);
            return newLead;
        });

        if (lead.getOwner() == null && owner != null) lead.setOwner(owner);
        if (lead.getTenant() == null && tenant != null) lead.setTenant(tenant);
        if (lead.getLeadNumber() == null || lead.getLeadNumber().isBlank()) lead.setLeadNumber(leadNumber);
        lead.setDeleted(false);
        lead.setStatus(Lead.LeadStatus.BOOKED);
        lead.setDealLabel(dealLabel);
        lead.setLastActivity(LocalDateTime.now());
        lead = leadRepository.save(lead);

        // Append enquiry details and publish event for email notification
        String summary = buildHumanSummary(normalizedMap, "Appointment Booking Flow");
        leadService.appendEnquiryToLead(lead, summary, "FLOW", "WhatsApp Flow: " + flowDisplayName, normalizedMap);
        eventPublisher.publishEvent(new LeadCreatedEvent(this, lead, "WHATSAPP_FLOW"));
        evictLeadCaches();

        if (tenant != null && webSocketPublisher != null) {
            Map<String, Object> wsPayload = new HashMap<>();
            wsPayload.put("type", "LEAD_CREATED");
            wsPayload.put("leadId", lead.getId().toString());
            wsPayload.put("leadNumber", lead.getLeadNumber());
            wsPayload.put("status", "BOOKED");
            webSocketPublisher.publishMessage(tenant.getId(), wsPayload);
        }

        log.info("📅 [FlowProcessor] Created/Updated Booked Lead for Contact {} via Flow and triggered email notifications", contact.getWaId());
    }

    private void processLeadGeneration(FlowSubmission submission, Contact contact, Map<String, String> normalizedMap, Map<String, Object> rawData) {
        if (contact == null) return;
        User owner = resolveOwner(submission, contact);
        Tenant tenant = resolveTenant(submission, contact, owner);

        if (contact != null) {
            boolean contactUpdated = false;
            if (contact.getOwner() == null && owner != null) {
                contact.setOwner(owner);
                contactUpdated = true;
            }
            if (contact.getTenant() == null && tenant != null) {
                contact.setTenant(tenant);
                contactUpdated = true;
            }
            if (contactUpdated) contactRepository.save(contact);
        }

        String service = normalizedMap.getOrDefault("serviceCategory", "Inquiry");
        String flowDisplayName = cleanFlowDisplayName(submission.getFlow(), "Sales Lead");
        String dealLabel = flowDisplayName + (service != null && !service.isBlank() && !"Inquiry".equalsIgnoreCase(service) ? " (" + service + ")" : "");

        String leadNumber = referenceNumberService.generate(owner, ReferenceNumberService.EntityType.LEAD);

        Optional<Lead> existingLead = leadRepository.findTopByContactOrderByCreatedAtDesc(contact);
        Lead lead = existingLead.orElseGet(() -> {
            Lead newLead = Lead.builder()
                    .contact(contact)
                    .status(Lead.LeadStatus.NEW)
                    .owner(owner)
                    .dealLabel(dealLabel)
                    .leadNumber(leadNumber)
                    .build();
            if (tenant != null) newLead.setTenant(tenant);
            return newLead;
        });

        if (lead.getOwner() == null && owner != null) lead.setOwner(owner);
        if (lead.getTenant() == null && tenant != null) lead.setTenant(tenant);
        if (lead.getLeadNumber() == null || lead.getLeadNumber().isBlank()) lead.setLeadNumber(leadNumber);
        lead.setDeleted(false);
        lead.setStatus(Lead.LeadStatus.NEW);
        lead.setDealLabel(dealLabel);
        lead.setLastActivity(LocalDateTime.now());
        lead = leadRepository.save(lead);

        // Append enquiry details and publish event for email notification
        String summary = buildHumanSummary(normalizedMap, "Lead Generation Flow");
        leadService.appendEnquiryToLead(lead, summary, "FLOW", "WhatsApp Flow: " + flowDisplayName, normalizedMap);
        eventPublisher.publishEvent(new LeadCreatedEvent(this, lead, "WHATSAPP_FLOW"));
        evictLeadCaches();

        if (tenant != null && webSocketPublisher != null) {
            Map<String, Object> wsPayload = new HashMap<>();
            wsPayload.put("type", "LEAD_CREATED");
            wsPayload.put("leadId", lead.getId().toString());
            wsPayload.put("leadNumber", lead.getLeadNumber());
            wsPayload.put("status", "NEW");
            webSocketPublisher.publishMessage(tenant.getId(), wsPayload);
        }

        log.info("🎯 [FlowProcessor] Created/Updated New Lead for Contact {} via Flow and triggered email notifications", contact.getWaId());
    }

    private void processCustomerSupport(FlowSubmission submission, Contact contact, Map<String, String> normalizedMap, Map<String, Object> rawData) {
        if (contact == null) return;
        User owner = resolveOwner(submission, contact);
        Tenant tenant = resolveTenant(submission, contact, owner);

        if (contact != null) {
            boolean contactUpdated = false;
            if (contact.getOwner() == null && owner != null) {
                contact.setOwner(owner);
                contactUpdated = true;
            }
            if (contact.getTenant() == null && tenant != null) {
                contact.setTenant(tenant);
                contactUpdated = true;
            }
            if (contactUpdated) contactRepository.save(contact);
        }

        String flowDisplayName = cleanFlowDisplayName(submission.getFlow(), "Customer Support");
        String category = normalizedMap.getOrDefault("serviceCategory", "General Support");
        String subject = normalizedMap.get("requirement");
        if (subject == null || subject.isBlank()) {
            subject = flowDisplayName + " (" + category + ")";
        }

        String description = buildHumanSummary(normalizedMap, "Support Request");

        String ticketNumber = ticketNumberGenerator.generateTicketNumber(owner);
        String referenceNumber = referenceNumberService.generate(owner, ReferenceNumberService.EntityType.TICKET);

        Ticket ticket = Ticket.builder()
                .ticketNumber(ticketNumber)
                .referenceNumber(referenceNumber)
                .owner(owner)
                .contact(contact)
                .subject(subject.length() > 200 ? subject.substring(0, 197) + "..." : subject)
                .description(description)
                .submitterName(normalizedMap.getOrDefault("name", contact.getName() != null ? contact.getName() : "WhatsApp User"))
                .submitterEmail(normalizedMap.getOrDefault("email", contact.getEmail()))
                .submitterPhone(contact.getWaId())
                .category(category)
                .source(Ticket.TicketSource.WHATSAPP)
                .status(Ticket.TicketStatus.OPEN)
                .priority(Ticket.TicketPriority.MEDIUM)
                .build();

        if (tenant != null) {
            ticket.setTenant(tenant);
        }

        slaService.calculateSlaDeadlines(ticket);
        Ticket savedTicket = ticketRepository.save(ticket);

        // Also track as a Lead / Enquiry so everything is unified in CRM timeline
        String leadNumber = referenceNumberService.generate(owner, ReferenceNumberService.EntityType.LEAD);

        Optional<Lead> existingLead = leadRepository.findTopByContactOrderByCreatedAtDesc(contact);
        Lead lead = existingLead.orElseGet(() -> {
            Lead newLead = Lead.builder()
                    .contact(contact)
                    .status(Lead.LeadStatus.NEW)
                    .owner(owner)
                    .dealLabel("Support: " + savedTicket.getTicketNumber())
                    .leadNumber(leadNumber)
                    .build();
            if (tenant != null) newLead.setTenant(tenant);
            return newLead;
        });
        if (lead.getOwner() == null && owner != null) lead.setOwner(owner);
        if (lead.getTenant() == null && tenant != null) lead.setTenant(tenant);
        if (lead.getLeadNumber() == null || lead.getLeadNumber().isBlank()) lead.setLeadNumber(leadNumber);
        lead.setDeleted(false);
        lead.setLastActivity(LocalDateTime.now());
        lead = leadRepository.save(lead);

        leadService.appendEnquiryToLead(lead, description, "FLOW", "WhatsApp Support: " + savedTicket.getTicketNumber(), normalizedMap);

        // Publish TicketCreatedEvent (sends ticket confirmation email with ticket number & SLA to customer + notification to owner)
        eventPublisher.publishEvent(new TicketCreatedEvent(this, savedTicket, "WHATSAPP_FLOW"));
        evictLeadCaches();

        log.info("🎫 [FlowProcessor] Created Support Ticket {} for contact {} via Flow and triggered ticket emails", savedTicket.getTicketNumber(), contact.getWaId());
    }

    private void processGeneralInquiry(FlowSubmission submission, Contact contact, Map<String, String> normalizedMap, Map<String, Object> rawData) {
        if (contact == null) return;
        User owner = resolveOwner(submission, contact);
        Tenant tenant = resolveTenant(submission, contact, owner);

        if (contact != null) {
            boolean contactUpdated = false;
            if (contact.getOwner() == null && owner != null) {
                contact.setOwner(owner);
                contactUpdated = true;
            }
            if (contact.getTenant() == null && tenant != null) {
                contact.setTenant(tenant);
                contactUpdated = true;
            }
            if (contactUpdated) contactRepository.save(contact);
        }

        String flowDisplayName = cleanFlowDisplayName(submission.getFlow(), "General Inquiry");
        String leadNumber = referenceNumberService.generate(owner, ReferenceNumberService.EntityType.LEAD);

        Optional<Lead> existingLead = leadRepository.findTopByContactOrderByCreatedAtDesc(contact);
        Lead lead = existingLead.orElseGet(() -> {
            Lead newLead = Lead.builder()
                    .contact(contact)
                    .status(Lead.LeadStatus.NEW)
                    .owner(owner)
                    .dealLabel(flowDisplayName)
                    .leadNumber(leadNumber)
                    .build();
            if (tenant != null) newLead.setTenant(tenant);
            return newLead;
        });
        if (lead.getOwner() == null && owner != null) lead.setOwner(owner);
        if (lead.getTenant() == null && tenant != null) lead.setTenant(tenant);
        if (lead.getLeadNumber() == null || lead.getLeadNumber().isBlank()) lead.setLeadNumber(leadNumber);
        lead.setDeleted(false);
        lead.setLastActivity(LocalDateTime.now());
        lead = leadRepository.save(lead);

        String summary = buildHumanSummary(normalizedMap, flowDisplayName);
        leadService.appendEnquiryToLead(lead, summary, "FLOW", "WhatsApp Flow: " + flowDisplayName, normalizedMap);
        eventPublisher.publishEvent(new LeadCreatedEvent(this, lead, "WHATSAPP_FLOW"));
        evictLeadCaches();
        log.info("ℹ️ [FlowProcessor] Processed general survey/inquiry for contact {}", contact.getWaId());
    }

    private String cleanFlowDisplayName(WhatsAppFlow flow, String defaultName) {
        if (flow == null || flow.getName() == null || flow.getName().isBlank()) {
            return defaultName;
        }
        String name = flow.getName().replaceAll("^[\\p{So}\\p{Sk}\\p{Sm}\\p{Sc}\\p{Punct}\\s]+", "").trim();
        if (name.toLowerCase().startsWith("master ")) {
            name = name.substring(7).trim();
        }
        return name.isBlank() ? defaultName : name;
    }

    /**
     * Maps heterogeneous Meta Flow field names into standardized CRM canonical keys.
     */
    public static Map<String, String> normalizeFlowSubmissionData(Map<String, Object> rawMap) {
        Map<String, String> normalized = new HashMap<>();
        if (rawMap == null) return normalized;

        // Keep raw map entries and prepare lowercased keys
        Map<String, String> input = new HashMap<>();
        for (Map.Entry<String, Object> entry : rawMap.entrySet()) {
            if (entry.getValue() != null) {
                String val = String.valueOf(entry.getValue()).trim();
                if (!val.isBlank()) {
                    input.put(entry.getKey().toLowerCase().replaceAll("[^a-z0-9_]", "_"), val);
                    normalized.put(entry.getKey(), val);
                }
            }
        }

        // 1. Name
        String name = findFirst(input, "name", "full_name", "fullname", "first_name", "firstname", "patient_name", "client_name", "customer_name", "student_name", "visitor_name", "user_name");
        if (name != null) normalized.put("name", name);

        // 2. Email
        String email = findFirst(input, "email", "email_address", "customer_email", "patient_email", "client_email", "mail", "user_email", "contact_email");
        if (email != null) normalized.put("email", email);

        // 3. Phone
        String phone = findFirst(input, "phone", "phone_number", "mobile", "mobile_number", "whatsapp", "whatsapp_number", "contact_number", "tel");
        if (phone != null) normalized.put("phone", phone);

        // 4. Service / Treatment / Category
        String service = findFirst(input, "service", "service_category", "servicecategory", "treatment", "treatment_type", "package", "consultation_type", "course", "specialty", "occasion", "shoot_type", "class_type", "service_type", "category");
        if (service != null) {
            normalized.put("serviceCategory", service);
            normalized.put("service_category", service);
        }

        // 5. Requirement / Notes / Description
        String requirement = findFirst(input, "requirement", "specific_requirement", "notes", "description", "details", "message", "query", "issue", "problem", "feedback", "comments", "remarks", "reason");
        if (requirement != null) {
            normalized.put("requirement", requirement);
            normalized.put("specific_requirement", requirement);
        }

        // 6. Preferred Date
        String preferredDate = findFirst(input, "preferred_date", "preferreddate", "date", "appointment_date", "booking_date", "event_date", "visit_date");
        if (preferredDate != null) {
            normalized.put("preferred_date", preferredDate);
            normalized.put("preferredDate", preferredDate);
        }

        // 7. Time Slot
        String timeSlot = findFirst(input, "time_slot", "timeslot", "preferred_time", "preferredtime", "time", "slot", "preferred_slot", "session_time");
        if (timeSlot != null) {
            normalized.put("time_slot", timeSlot);
            normalized.put("time", timeSlot);
        }

        // 8. Budget
        String budget = findFirst(input, "budget", "expected_budget", "price_range", "budget_range");
        if (budget != null) normalized.put("budget", budget);

        // 9. City / Location
        String city = findFirst(input, "city", "location", "branch", "area");
        if (city != null) normalized.put("city", city);

        // 10. Country
        String country = findFirst(input, "country");
        if (country != null) normalized.put("country", country);

        // 11. Age & Gender
        String age = findFirst(input, "age");
        if (age != null) normalized.put("age", age);
        String gender = findFirst(input, "gender");
        if (gender != null) normalized.put("gender", gender);

        // 12. Address & Pincode
        String address = findFirst(input, "address", "full_address", "street");
        if (address != null) normalized.put("address", address);
        String pincode = findFirst(input, "pincode", "zipcode", "postal_code", "pin");
        if (pincode != null) normalized.put("pincode", pincode);

        // 13. Company
        String company = findFirst(input, "company", "business_name", "organization", "firm");
        if (company != null) normalized.put("company", company);

        return normalized;
    }

    private static String findFirst(Map<String, String> map, String... keys) {
        for (String k : keys) {
            String val = map.get(k);
            if (val != null && !val.isBlank()) {
                return val;
            }
        }
        return null;
    }

    private String buildHumanSummary(Map<String, String> data, String defaultTitle) {
        if (data == null || data.isEmpty()) return defaultTitle;
        StringBuilder sb = new StringBuilder();
        sb.append(defaultTitle).append(" Details:\n");

        String[] priorityKeys = {"name", "email", "phone", "serviceCategory", "preferred_date", "time_slot", "budget", "city", "company", "requirement"};
        Map<String, String> labelMap = Map.of(
                "name", "Full Name",
                "email", "Email",
                "phone", "Phone",
                "serviceCategory", "Service / Treatment",
                "preferred_date", "Preferred Date",
                "time_slot", "Preferred Time",
                "budget", "Budget",
                "city", "City / Location",
                "company", "Company",
                "requirement", "Requirements / Notes"
        );

        java.util.Set<String> processedKeys = new java.util.HashSet<>();

        for (String pk : priorityKeys) {
            if (data.containsKey(pk) && data.get(pk) != null && !data.get(pk).isBlank()) {
                sb.append("• ").append(labelMap.getOrDefault(pk, pk)).append(": ").append(data.get(pk)).append("\n");
                processedKeys.add(pk);
                processedKeys.add(pk.toLowerCase());
            }
        }

        for (Map.Entry<String, String> entry : data.entrySet()) {
            String k = entry.getKey();
            if (!processedKeys.contains(k) && !processedKeys.contains(k.toLowerCase()) &&
                    !k.equals("service_category") && !k.equals("specific_requirement") && !k.equals("preferredDate") && !k.equals("time") &&
                    entry.getValue() != null && !entry.getValue().isBlank()) {
                String rawKey = k.replace('_', ' ');
                String prettyKey = java.util.Arrays.stream(rawKey.split(" "))
                        .filter(w -> !w.isBlank())
                        .map(w -> Character.toUpperCase(w.charAt(0)) + (w.length() > 1 ? w.substring(1) : ""))
                        .collect(java.util.stream.Collectors.joining(" "));
                sb.append("• ").append(prettyKey).append(": ").append(entry.getValue()).append("\n");
            }
        }

        return sb.toString().trim();
    }
}
