package com.chatcrmlite.backend.event;

import com.chatcrmlite.backend.models.*;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.services.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Email notification listener.
 *
 * Architecture:
 *   Domain Service (inside @Transactional) → publishEvent(...)
 *       → EmailNotificationListener.on*()  [synchronous, session still open]
 *           → EmailService.sendTemplate()  [@Async — SMTP on background thread]
 *
 * WHY synchronous listener + async send:
 *   If the listener were @Async, it would run in a new thread with NO Hibernate
 *   session, causing LazyInitializationException on every lazy-loaded field
 *   (owner.email, contact.email, etc.) — silently swallowed, no email sent.
 *
 *   By keeping the listener synchronous, all entity fields are read while the
 *   original transaction/session is still active. The actual SMTP dispatch is
 *   then handed off to a background thread via @Async on sendTemplate().
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailNotificationListener {

    private final EmailService emailService;
    private final ContactRepository contactRepository;
    private final com.chatcrmlite.backend.repositories.LeadEnquiryRepository leadEnquiryRepository;

    private static final DateTimeFormatter DT_FMT =
            DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a");

    // ══════════════════════════════════════════════════════════════════════
    //  Ticket Events
    // ══════════════════════════════════════════════════════════════════════

    @EventListener
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW, readOnly = true)
    public void onTicketCreated(TicketCreatedEvent event) {
        Ticket t = event.getTicket();
        log.debug("[EmailListener] TicketCreatedEvent ticket={}", t.getTicketNumber());

        // 1. Confirmation to the customer
        if (t.getSubmitterEmail() != null && !t.getSubmitterEmail().isBlank()) {
            emailService.sendTicketCreatedToCustomer(
                    t.getSubmitterEmail(),
                    t.getSubmitterName() != null ? t.getSubmitterName() : "Customer",
                    t.getTicketNumber(),
                    t.getSubject(),
                    t.getDescription(),
                    t.getPriority() != null ? t.getPriority().name() : "MEDIUM"
            );
        }

        // 2. Notification to the tenant/owner
        if (t.getOwner() != null && t.getOwner().getEmail() != null) {
            emailService.sendTicketCreatedToOwner(
                    t.getOwner().getEmail(),
                    displayName(t.getOwner()),
                    t.getTicketNumber(),
                    t.getSubject(),
                    t.getSubmitterName() != null ? t.getSubmitterName() : "Customer",
                    t.getSubmitterEmail() != null ? t.getSubmitterEmail() : "",
                    t.getPriority() != null ? t.getPriority().name() : "MEDIUM",
                    t.getDescription()
            );
        }
    }

    @EventListener
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW, readOnly = true)
    public void onTicketStatusChanged(TicketStatusChangedEvent event) {
        Ticket t = event.getTicket();
        log.debug("[EmailListener] TicketStatusChangedEvent ticket={} {} → {}",
                t.getTicketNumber(), event.getOldStatus(), event.getNewStatus());

        if (t.getSubmitterEmail() == null || t.getSubmitterEmail().isBlank()) return;

        emailService.sendTicketStatusUpdate(
                t.getSubmitterEmail(),
                t.getSubmitterName() != null ? t.getSubmitterName() : "Customer",
                t.getTicketNumber(),
                t.getSubject(),
                event.getOldStatus().name(),
                event.getNewStatus().name()
        );
    }

    @EventListener
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW, readOnly = true)
    public void onTicketAssigned(TicketAssignedEvent event) {
        Ticket t = event.getTicket();
        User agent = event.getAgent();
        log.debug("[EmailListener] TicketAssignedEvent ticket={} agent={}", t.getTicketNumber(), agent.getEmail());

        if (agent.getEmail() == null || agent.getEmail().isBlank()) return;

        emailService.sendTicketAssignedToAgent(
                agent.getEmail(),
                displayName(agent),
                t.getTicketNumber(),
                t.getSubject(),
                t.getSubmitterName() != null ? t.getSubmitterName() : "Customer",
                t.getPriority() != null ? t.getPriority().name() : "MEDIUM"
        );
    }

    @EventListener
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW, readOnly = true)
    public void onTicketCommentAdded(TicketCommentAddedEvent event) {
        Ticket t = event.getTicket();
        log.debug("[EmailListener] TicketCommentAddedEvent ticket={}", t.getTicketNumber());

        if (t.getSubmitterEmail() == null || t.getSubmitterEmail().isBlank()) return;

        emailService.sendTicketCommentNotification(
                t.getSubmitterEmail(),
                t.getSubmitterName() != null ? t.getSubmitterName() : "Customer",
                t.getTicketNumber(),
                t.getSubject(),
                event.getAuthorName(),
                event.getCommentMessage()
        );
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Lead Events
    // ══════════════════════════════════════════════════════════════════════

    @EventListener
    @Transactional  // Remove readOnly since we might update contact.email
    public void onLeadCreated(LeadCreatedEvent event) {
        Lead lead = event.getLead();
        log.info("[EmailListener] LeadCreatedEvent lead={} source={}", lead.getId(), event.getSource());

        Contact contact = lead.getContact();

        // Resolve email: contact.email first, then try to extract from enquiry data
        String toEmail = null;
        String contactName = "there";

        if (contact != null) {
            toEmail = contact.getEmail();
            if (contact.getName() != null && !contact.getName().isBlank()) {
                contactName = contact.getName();
            }
        }

        // Try to get latest enquiry to extract details
        java.util.List<LeadEnquiry> enquiries = leadEnquiryRepository.findAllByLeadOrderByCreatedAtDesc(lead);
        LeadEnquiry latestEnquiry = enquiries.isEmpty() ? null : enquiries.get(0);
        
        String enquiryMessage = "";
        
        if (latestEnquiry != null) {
            // Build a comprehensive message for the email
            StringBuilder sb = new StringBuilder();
            if (latestEnquiry.getMessage() != null && !latestEnquiry.getMessage().isBlank()) {
                sb.append(latestEnquiry.getMessage()).append("\n\n");
            }
            if (latestEnquiry.getRequirement() != null && !latestEnquiry.getRequirement().isBlank()) {
                sb.append("Requirement:\n").append(latestEnquiry.getRequirement()).append("\n\n");
            }
            if (latestEnquiry.getBudget() != null && !latestEnquiry.getBudget().isBlank()) sb.append("Budget: ").append(latestEnquiry.getBudget()).append("\n");
            if (latestEnquiry.getAge() != null && !latestEnquiry.getAge().isBlank()) sb.append("Age: ").append(latestEnquiry.getAge()).append("\n");
            if (latestEnquiry.getGender() != null && !latestEnquiry.getGender().isBlank()) sb.append("Gender: ").append(latestEnquiry.getGender()).append("\n");
            if (latestEnquiry.getAddress() != null && !latestEnquiry.getAddress().isBlank()) sb.append("Address: ").append(latestEnquiry.getAddress()).append("\n");
            if (latestEnquiry.getPincode() != null && !latestEnquiry.getPincode().isBlank()) sb.append("Pincode: ").append(latestEnquiry.getPincode()).append("\n");
            if (latestEnquiry.getPreferredDate() != null && !latestEnquiry.getPreferredDate().isBlank()) sb.append("Preferred Date: ").append(latestEnquiry.getPreferredDate()).append("\n");
            
            enquiryMessage = sb.toString().trim();
            
            if (toEmail == null || toEmail.isBlank()) {
                if (latestEnquiry.getEmail() != null && !latestEnquiry.getEmail().isBlank()) {
                    toEmail = latestEnquiry.getEmail();
                } else {
                    toEmail = extractEmailFromEnquiry(lead.getEnquiries());
                }
                
                if (toEmail != null && !toEmail.isBlank()) {
                    log.info("[EmailListener] Lead {} — extracted email {} from enquiry data", lead.getId(), toEmail);
                    if (contact != null && (contact.getEmail() == null || contact.getEmail().isBlank())) {
                        contact.setEmail(toEmail);
                        contactRepository.save(contact);
                        log.info("[EmailListener] Updated contact {} email to {}", contact.getWaId(), toEmail);
                    }
                } else {
                    log.info("[EmailListener] Lead {} — contact has no email, skipping customer notification", lead.getId());
                }
            }
        } else {
            // Fallback for old leads
            if (toEmail == null || toEmail.isBlank()) {
                toEmail = extractEmailFromEnquiry(lead.getEnquiries());
                if (toEmail != null) {
                    if (contact != null && (contact.getEmail() == null || contact.getEmail().isBlank())) {
                        contact.setEmail(toEmail);
                        contactRepository.save(contact);
                    }
                }
            }
            enquiryMessage = firstEnquiryMessage(lead.getEnquiries());
        }

        // Send customer confirmation if we have an email
        if (toEmail != null && !toEmail.isBlank()) {
            String businessName = lead.getOwner() != null ? displayName(lead.getOwner()) : "our team";
            log.info("[EmailListener] Sending lead enquiry email to {} for lead={}", toEmail, lead.getId());
            emailService.sendLeadCreatedToContact(toEmail, contactName, businessName, enquiryMessage);
        }

        // Always notify the owner
        if (lead.getOwner() != null && lead.getOwner().getEmail() != null) {
            String ownerEmail = lead.getOwner().getEmail();
            String ownerName = displayName(lead.getOwner());
            String contactEmail = toEmail != null ? toEmail : "";
            log.info("[EmailListener] Sending new lead notification to owner {}", ownerEmail);
            emailService.sendNewLeadToOwner(ownerEmail, ownerName, contactName, contactEmail,
                    enquiryMessage, event.getSource());
        }
    }

    @EventListener
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW, readOnly = true)
    public void onLeadStatusChanged(LeadStatusChangedEvent event) {
        Lead lead = event.getLead();
        log.debug("[EmailListener] LeadStatusChangedEvent lead={} {} → {}",
                lead.getId(), event.getOldStatus(), event.getNewStatus());

        Contact contact = lead.getContact();
        String businessName = lead.getOwner() != null ? displayName(lead.getOwner()) : "our team";

        // CLOSED_WON → notify both customer and owner
        if (event.getNewStatus() == Lead.LeadStatus.CLOSED_WON) {
            if (contact != null && contact.getEmail() != null && !contact.getEmail().isBlank()) {
                emailService.sendLeadClosedWon(
                        contact.getEmail(),
                        contact.getName() != null ? contact.getName() : "there",
                        businessName,
                        lead.getDealLabel()
                );
            }
            if (lead.getOwner() != null && lead.getOwner().getEmail() != null) {
                emailService.sendLeadClosedWonToOwner(
                        lead.getOwner().getEmail(),
                        displayName(lead.getOwner()),
                        contact != null ? contact.getName() : "Unknown",
                        contact != null ? contact.getEmail() : "",
                        lead.getDealLabel(),
                        lead.getDealValue() != null ? lead.getDealValue().toPlainString() : null,
                        lead.getCurrency()
                );
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Appointment Events
    // ══════════════════════════════════════════════════════════════════════

    @EventListener
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW, readOnly = true)
    public void onAppointmentScheduled(AppointmentScheduledEvent event) {
        Appointment appt = event.getAppointment();
        log.info("[EmailListener] AppointmentScheduledEvent appt={} status={}",
                appt.getId(), appt.getStatus());

        Contact contact = appt.getContact();
        String contactEmail = resolveEmail(contact, appt.getCollectedData());
        String contactName  = contact != null && contact.getName() != null ? contact.getName() : "there";
        String businessName = appt.getOwner() != null ? displayName(appt.getOwner()) : "our team";
        String dateTime     = appt.getAppointmentDateTime() != null
                ? appt.getAppointmentDateTime().format(DT_FMT) : "TBD";

        switch (appt.getStatus()) {
            case SCHEDULED -> {
                // Confirmation to customer
                if (contactEmail != null) {
                    emailService.sendAppointmentConfirmation(
                            contactEmail, contactName,
                            appt.getTitle(), dateTime,
                            businessName, appt.getMeetingLink()
                    );
                }
                // Notification to owner
                if (appt.getOwner() != null && appt.getOwner().getEmail() != null) {
                    emailService.sendAppointmentCreatedToOwner(
                            appt.getOwner().getEmail(),
                            displayName(appt.getOwner()),
                            contactName,
                            contactEmail != null ? contactEmail : "",
                            appt.getTitle(), dateTime
                    );
                }
            }
            case CANCELLED -> {
                if (contactEmail != null) {
                    emailService.sendAppointmentCancelled(
                            contactEmail, contactName,
                            appt.getTitle(), dateTime, businessName
                    );
                }
            }
            case COMPLETED -> {
                if (contactEmail != null) {
                    emailService.sendAppointmentCompleted(
                            contactEmail, contactName,
                            appt.getTitle(), businessName
                    );
                }
            }
            default -> log.debug("[EmailListener] No email action for appointment status={}", appt.getStatus());
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Booking Events
    // ══════════════════════════════════════════════════════════════════════

    @EventListener
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW, readOnly = true)
    public void onBookingConfirmed(BookingConfirmedEvent event) {
        Booking booking = event.getBooking();
        log.info("[EmailListener] BookingConfirmedEvent booking={} status={}",
                booking.getId(), booking.getStatus());

        Contact contact = booking.getContact();
        String contactEmail = resolveEmail(contact, booking.getCollectedData());
        String contactName  = contact != null && contact.getName() != null ? contact.getName() : "there";
        String businessName = booking.getOwner() != null ? displayName(booking.getOwner()) : "our team";

        switch (booking.getStatus()) {
            case CONFIRMED -> {
                // Confirmation to customer
                if (contactEmail != null) {
                    emailService.sendBookingConfirmation(
                            contactEmail, contactName,
                            booking.getService(), booking.getPreferredSlot(),
                            businessName
                    );
                }
                // Notification to owner
                if (booking.getOwner() != null && booking.getOwner().getEmail() != null) {
                    emailService.sendBookingCreatedToOwner(
                            booking.getOwner().getEmail(),
                            displayName(booking.getOwner()),
                            contactName,
                            contactEmail != null ? contactEmail : "",
                            booking.getService(), booking.getPreferredSlot()
                    );
                }
            }
            case CANCELLED -> {
                if (contactEmail != null) {
                    emailService.sendBookingCancelled(
                            contactEmail, contactName,
                            booking.getService(), booking.getPreferredSlot(),
                            businessName
                    );
                }
            }
            case COMPLETED -> {
                if (contactEmail != null) {
                    emailService.sendBookingCompleted(
                            contactEmail, contactName,
                            booking.getService(), businessName
                    );
                }
            }
            default -> log.debug("[EmailListener] No email action for booking status={}", booking.getStatus());
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════════════════════════

    private String displayName(User user) {
        if (user == null) return "Team";
        return user.getDisplayName() != null && !user.getDisplayName().isBlank()
                ? user.getDisplayName()
                : user.getEmail();
    }

    private String resolveEmail(Contact contact, String collectedDataJson) {
        // Try to extract from collectedData JSON first (prioritize newly provided email in flow)
        if (collectedDataJson != null && collectedDataJson.contains("\"email\"")) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper =
                        new com.fasterxml.jackson.databind.ObjectMapper();
                Map<?, ?> data = mapper.readValue(collectedDataJson, Map.class);
                Object emailVal = data.get("email");
                if (emailVal instanceof String s && !s.isBlank()) {
                    return s;
                }
            } catch (Exception e) {
                log.warn("[EmailListener] Could not parse collectedData for email: {}", e.getMessage());
            }
        }
        
        // Fallback to the existing contact email
        if (contact != null && contact.getEmail() != null && !contact.getEmail().isBlank()) {
            return contact.getEmail();
        }
        
        return null;
    }

    /**
     * Extracts email from enquiry message text.
     * Looks for patterns like "Email: user@domain.com" in the enquiry message.
     */
    private String extractEmailFromEnquiry(String enquiriesJson) {
        String enquiryMessage = firstEnquiryMessage(enquiriesJson);
        if (enquiryMessage == null || enquiryMessage.isBlank()) {
            return null;
        }

        // Look for "Email: " pattern in the message
        String[] lines = enquiryMessage.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.toLowerCase().startsWith("email:")) {
                String emailPart = trimmed.substring(6).trim(); // Remove "Email: "
                // Basic email validation
                if (emailPart.contains("@") && emailPart.contains(".")) {
                    return emailPart;
                }
            }
        }
        return null;
    }

    /**
     * Extracts the first enquiry message from the lead's JSON enquiries array.
     * Returns null if the array is empty or unparseable.
     */
    private String firstEnquiryMessage(String enquiriesJson) {
        if (enquiriesJson == null || enquiriesJson.isBlank() || "[]".equals(enquiriesJson.trim())) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            java.util.List<?> list = mapper.readValue(enquiriesJson, java.util.List.class);
            if (!list.isEmpty() && list.get(0) instanceof Map<?, ?> first) {
                Object msg = first.get("message");
                return msg instanceof String s ? s : null;
            }
        } catch (Exception e) {
            log.warn("[EmailListener] Could not parse enquiries JSON: {}", e.getMessage());
        }
        return null;
    }
}
