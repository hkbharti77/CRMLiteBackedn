package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.config.RateLimitConfig;
import com.chatcrmlite.backend.dto.ContactUsRequest;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.LeadRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.EmailService;
import com.chatcrmlite.backend.services.lead.LeadService;
import io.github.bucket4j.Bucket;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/public")
public class PublicContactController {
    private static final Logger log = LoggerFactory.getLogger(PublicContactController.class);

    @Autowired private UserRepository userRepository;
    @Autowired private ContactRepository contactRepository;
    @Autowired private LeadRepository leadRepository;
    @Autowired private LeadService leadService;
    @Autowired private EmailService emailService;
    @Autowired private RateLimitConfig rateLimitConfig;

    @PostMapping("/contact/{businessId}")
    public ResponseEntity<Map<String, String>> submitContactUs(
            @PathVariable UUID businessId,
            @Valid @RequestBody ContactUsRequest req,
            jakarta.servlet.http.HttpServletRequest request) {

        String ipAddress = getClientIP(request);
        Bucket bucket = rateLimitConfig.resolveBucket(ipAddress);

        if (!bucket.tryConsume(1)) {
            log.warn("[PublicContact] Rate limit exceeded for IP: {}", ipAddress);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "Too many requests. Please try again later."));
        }

        User owner = userRepository.findById(businessId)
                .orElseThrow(() -> new BusinessNotFoundException(businessId));

        // 1. Find or Create Contact
        Contact contact = contactRepository.findFirstByEmailAndTenant_Id(req.getEmail(), owner.getTenant().getId())
                .orElseGet(() -> {
                    Contact newContact = new Contact();
                    newContact.setEmail(req.getEmail());
                    newContact.setName(req.getName());
                    newContact.setWaId(req.getPhone() != null && !req.getPhone().isEmpty() ? req.getPhone() : req.getEmail());
                    newContact.setTenant(owner.getTenant());
                    newContact.setOwner(owner);
                    newContact.setSource("Website Contact Form");
                    return contactRepository.save(newContact);
                });

        // Update phone if missing in DB but provided in request
        if (req.getPhone() != null && !req.getPhone().isEmpty() && (contact.getWaId() == null || contact.getWaId().isEmpty() || contact.getWaId().equals(contact.getEmail()))) {
            contact.setWaId(req.getPhone());
            contactRepository.save(contact);
        }

        // 2. Create Lead
        Lead lead = new Lead();
        lead.setContact(contact);
        lead.setTenant(owner.getTenant());
        lead.setOwner(owner);
        lead.setStatus(Lead.LeadStatus.NEW);
        Lead savedLead = leadRepository.save(lead);

        // 3. Append Enquiry
        String messageBody = (req.getSubject() != null && !req.getSubject().isEmpty() ? "Subject: " + req.getSubject() + "\n\n" : "") + req.getMessage();
        leadService.appendEnquiryToLead(savedLead, messageBody, "WEBSITE_FORM", "Contact Us", Map.of(
            "name", req.getName(),
            "email", req.getEmail(),
            "phone", req.getPhone() != null ? req.getPhone() : ""
        ));

        // 4. Send Receipt Email
        String businessName = owner.getTenant().getBusinessName();
        if (businessName == null || businessName.isEmpty()) {
            businessName = owner.getBusinessName();
        }
        try {
            emailService.sendContactUsReceiptEmail(req.getEmail(), req.getName(), businessName);
        } catch (Exception e) {
            log.error("[PublicContact] Failed to send receipt email to {}", req.getEmail(), e);
        }

        log.info("[PublicContact] Form submitted for business={} from email={} IP={}", businessId, req.getEmail(), ipAddress);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of(
                        "message", "✅ Thank you for contacting us! We've received your request."
                ));
    }

    private String getClientIP(jakarta.servlet.http.HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }

    public static class BusinessNotFoundException extends RuntimeException {
        public BusinessNotFoundException(UUID id) {
            super("Business not found: " + id);
        }
    }
}
