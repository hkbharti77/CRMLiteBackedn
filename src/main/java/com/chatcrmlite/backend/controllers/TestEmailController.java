package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.models.EmailTemplate;
import com.chatcrmlite.backend.services.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.math.BigDecimal;
import java.util.*;

/**
 * Controller to trigger test emails for all 25+ enterprise templates to a specified email address.
 * The default recipient is the ADMIN_EMAIL configured in the application environment.
 */
@RestController
@RequestMapping("/api/v1/test-emails")
@org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
@CrossOrigin(origins = "*")
public class TestEmailController {

    private static final Logger log = LoggerFactory.getLogger(TestEmailController.class);

    @org.springframework.beans.factory.annotation.Value("${ADMIN_EMAIL:admin@example.com}")
    private String defaultRecipient;

    @Autowired
    private EmailService emailService;

    @Autowired
    private TemplateEngine templateEngine;

    /**
     * Send all 26 enterprise email templates to hkbharti77@gmail.com (or specified recipient).
     * Supports both GET and POST for easy browser testing.
     */
    @RequestMapping(value = "/send-all", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<?> sendAllTestEmails(
            @RequestParam(required = false) String to) {
        
        String recipient = (to != null && !to.isBlank()) ? to.trim() : defaultRecipient;
        log.info("[TestEmailController] Dispatching all 26 test emails to: {}", recipient);
        List<String> sentList = new ArrayList<>();

        try {
            // ── 1. Authentication & Security ──────────────────────────────────────────
            emailService.sendLoginOtp(
                recipient, 
                "749215", 
                "103.21.244.18", 
                "Chrome 127.0 (macOS 14.5)", 
                "2026-08-08 22:55:00 UTC"
            );
            sentList.add("1. login-otp (Login Verification Code: 749215)");

            emailService.sendPasswordResetOtp(
                recipient, 
                "839104"
            );
            sentList.add("2. password-reset-otp (Password Recovery Code: 839104)");

            // ── 2. Platform & Support Ticketing ──────────────────────────────────────
            emailService.sendPlatformTicketCreatedNotification(
                recipient,
                "Himanshu Bharti",
                "TKT-88421",
                "API Gateway Webhook Latency",
                "We noticed webhooks taking >500ms to acknowledge during peak traffic hours."
            );
            sentList.add("3. ticket-created-customer (Platform Support: TKT-88421)");

            emailService.sendTicketCreatedToCustomer(
                recipient,
                "Himanshu Bharti",
                "TKT-99214",
                "Unable to Sync WhatsApp Contacts",
                "WhatsApp contact synchronization failed with error code 401. Please investigate.",
                "HIGH"
            );
            sentList.add("4. ticket-created-customer (Customer Ticket: TKT-99214)");

            emailService.sendTicketCreatedToOwner(
                recipient,
                "Workspace Administrator",
                "TKT-99214",
                "Unable to Sync WhatsApp Contacts",
                "Himanshu Bharti",
                recipient,
                "HIGH",
                "WhatsApp contact synchronization failed with error code 401. Please investigate."
            );
            sentList.add("5. ticket-created-owner (Owner Alert: Ticket TKT-99214)");

            emailService.sendTicketStatusUpdate(
                recipient,
                "Himanshu Bharti",
                "TKT-99214",
                "Unable to Sync WhatsApp Contacts",
                "IN_PROGRESS",
                "RESOLVED"
            );
            sentList.add("6. ticket-status-update (Ticket Status: IN_PROGRESS -> RESOLVED)");

            emailService.sendTicketAssignedToAgent(
                recipient,
                "Himanshu Bharti",
                "TKT-99214",
                "Unable to Sync WhatsApp Contacts",
                "Himanshu Bharti",
                "HIGH"
            );
            sentList.add("7. ticket-assigned-agent (Work Item Assigned: TKT-99214)");

            emailService.sendTicketCommentNotification(
                recipient,
                "Himanshu Bharti",
                "TKT-99214",
                "Unable to Sync WhatsApp Contacts",
                "Senior AI Engineer",
                "We have verified your webhook endpoints and WhatsApp sync is now functioning normally."
            );
            sentList.add("8. ticket-comment-customer (New Reply on Ticket TKT-99214)");

            // ── 3. Leads & CRM Sales ──────────────────────────────────────────────────
            emailService.sendLeadCreatedToContact(
                recipient,
                "Himanshu Bharti",
                "GyanVaniAi Enterprise",
                "I am interested in the AI-powered CRM Suite with 50 agent seats and WhatsApp automation."
            );
            sentList.add("9. lead-enquiry-received (Customer Enquiry Acknowledgment)");

            emailService.sendNewLeadToOwner(
                recipient,
                "Sales VP",
                "Himanshu Bharti",
                recipient,
                "Prospect requesting high-volume WhatsApp campaign routing and custom webhook integrations.",
                "Website Interactive Contact Form"
            );
            sentList.add("10. ticket-created-owner (New Lead Alert to Owner)");

            emailService.sendLeadClosedWon(
                recipient,
                "Himanshu Bharti",
                "GyanVaniAi Enterprise",
                "Annual Enterprise AI CRM Tier"
            );
            sentList.add("11. lead-closed-won-customer (Deal Confirmed - Customer Celebration)");

            emailService.sendLeadClosedWonToOwner(
                recipient,
                "Executive Leadership",
                "Himanshu Bharti",
                recipient,
                "Annual Enterprise AI CRM Tier",
                "150,000",
                "₹"
            );
            sentList.add("12. lead-closed-won-owner (Revenue Won - Owner Celebration: ₹150,000)");

            EmailTemplate followbackTemplate = new EmailTemplate();
            followbackTemplate.setSubject("Following up on your GyanVaniAi Enterprise Demo");
            followbackTemplate.setContent(
                "<p>Thank you for taking the time to discuss your CRM needs with us today.</p>" +
                "<p>We have tailored an implementation roadmap specifically for your 50-agent team, including:</p>" +
                "<ul>" +
                "  <li>Multi-agent WhatsApp Inbox with Auto-routing</li>" +
                "  <li>AI Agent Knowledge Base &amp; RAG Integration</li>" +
                "  <li>Real-time webhook and analytics dashboard</li>" +
                "</ul>" +
                "<p>Please let us know if you would like to schedule our technical setup session this week.</p>"
            );
            emailService.sendAutomatedFollowback(
                recipient,
                "Himanshu Bharti",
                "GyanVaniAi Enterprise",
                followbackTemplate
            );
            sentList.add("13. lead-followback (Automated Lead Nurture Follow-up)");

            emailService.sendHighValueLeadAlert(
                recipient,
                "Sales Director",
                "Himanshu Bharti (Enterprise Prospect)",
                94
            );
            sentList.add("14. high-value-lead-alert (AI Intent Score Alert: 94/100)");

            // ── 4. Calendar Appointments ──────────────────────────────────────────────
            emailService.sendAppointmentConfirmation(
                recipient,
                "Himanshu Bharti",
                "Enterprise Architecture & Deployment Review",
                "August 12, 2026 at 3:30 PM IST",
                "GyanVaniAi Enterprise",
                "https://meet.google.com/gyan-vani-demo"
            );
            sentList.add("15. appointment-confirmation (Calendar Event Confirmed)");

            emailService.sendAppointmentCancelled(
                recipient,
                "Himanshu Bharti",
                "Initial Technical Scoping Call",
                "August 10, 2026 at 10:00 AM IST",
                "GyanVaniAi Enterprise"
            );
            sentList.add("16. appointment-cancelled (Calendar Event Cancelled)");

            emailService.sendAppointmentCompleted(
                recipient,
                "Himanshu Bharti",
                "Enterprise Architecture & Deployment Review",
                "GyanVaniAi Enterprise"
            );
            sentList.add("17. appointment-completed (Appointment Completed Wrap-up)");

            emailService.sendAppointmentCreatedToOwner(
                recipient,
                "Account Executive",
                "Himanshu Bharti",
                recipient,
                "Enterprise Architecture & Deployment Review",
                "August 12, 2026 at 3:30 PM IST"
            );
            sentList.add("18. appointment-owner (Owner Notification: Scheduled Appointment)");

            // ── 5. Service Bookings ───────────────────────────────────────────────────
            emailService.sendBookingConfirmation(
                recipient,
                "Himanshu Bharti",
                "Custom AI Flow & Agent Training Session",
                "August 15, 2026 at 2:00 PM IST",
                "GyanVaniAi Enterprise"
            );
            sentList.add("19. booking-confirmation (Booking Confirmed)");

            emailService.sendBookingCancelled(
                recipient,
                "Himanshu Bharti",
                "Custom AI Flow & Agent Training Session",
                "August 15, 2026 at 2:00 PM IST",
                "GyanVaniAi Enterprise"
            );
            sentList.add("20. booking-cancelled (Booking Cancelled)");

            emailService.sendBookingCompleted(
                recipient,
                "Himanshu Bharti",
                "Custom AI Flow & Agent Training Session",
                "GyanVaniAi Enterprise"
            );
            sentList.add("21. booking-completed (Booking Completed)");

            emailService.sendBookingCreatedToOwner(
                recipient,
                "Head of Customer Success",
                "Himanshu Bharti",
                recipient,
                "Custom AI Flow & Agent Training Session",
                "August 15, 2026 at 2:00 PM IST"
            );
            sentList.add("22. booking-owner (Owner Notification: New Booking Request)");

            // ── 6. Staff & Workspace Administration ──────────────────────────────────
            emailService.sendStaffWelcomeEmail(
                recipient,
                "Himanshu Bharti",
                "GyanVaniAi Enterprise Workspace",
                "Senior Workspace Administrator"
            );
            sentList.add("23. welcome-staff (Staff Onboarding & Invitation)");

            emailService.sendStaffStatusChangeEmail(
                recipient,
                "Himanshu Bharti",
                "GyanVaniAi Enterprise Workspace",
                false,
                "Account privileges reviewed and verified active by security administration."
            );
            sentList.add("24. staff-status-update-email (Staff Member Status: Active / Unblocked)");

            emailService.sendOwnerStaffStatusNotification(
                recipient,
                "Chief Security Officer",
                "Himanshu Bharti",
                recipient,
                false,
                "Account privileges reviewed and verified active by security administration."
            );
            sentList.add("25. staff-status-owner-notification (Owner Audit Notice: Staff Active)");

            // ── 7. Payment Link & Invoicing ──────────────────────────────────────────
            emailService.sendPaymentLinkEmail(
                recipient,
                "https://gyanvaniai.online/pay/inv_998124_enterprise",
                new BigDecimal("150000.00")
            );
            sentList.add("26. email-template (Secure Payment Link Invoice: ₹150,000.00)");

            return ResponseEntity.ok(Map.of(
                "status", "success",
                "recipient", recipient,
                "totalEmailsSent", sentList.size(),
                "message", "Successfully dispatched all 26 enterprise email templates to " + recipient,
                "dispatchedEmails", sentList
            ));

        } catch (Exception e) {
            log.error("[TestEmailController] Failed to dispatch test emails: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                "status", "error",
                "recipient", recipient,
                "message", e.getMessage(),
                "sentBeforeError", sentList
            ));
        }
    }

    /**
     * Send a single specific template on demand to test formatting.
     */
    @PostMapping("/send-template/{templateName}")
    public ResponseEntity<?> sendSingleTemplate(
            @PathVariable String templateName,
            @RequestParam(required = false) String to,
            @RequestBody(required = false) Map<String, Object> customVars) {

        String recipient = (to != null && !to.isBlank()) ? to.trim() : defaultRecipient;
        try {
            Context ctx = new Context();
            ctx.setVariable("heading", "Test Preview: " + templateName);
            ctx.setVariable("greeting", "Hi Himanshu Bharti,");
            ctx.setVariable("intro", "This is a single template preview test dispatched for verification.");
            ctx.setVariable("footerNote", "Testing enterprise typography, dark mode, and mobile layout.");
            ctx.setVariable("ctaLabel", "Open Portal");
            ctx.setVariable("ctaUrl", "https://gyanvaniai.online");
            ctx.setVariable("businessName", "GyanVaniAi Enterprise");
            
            // Populate common template variables
            ctx.setVariable("otpCode", "918273");
            ctx.setVariable("expiryMinutes", "10");
            ctx.setVariable("userEmail", recipient);
            ctx.setVariable("loginTime", "2026-08-08 22:55:00 UTC");
            ctx.setVariable("ipAddress", "103.21.244.18");
            ctx.setVariable("userAgent", "Chrome 127.0 (macOS 14.5)");
            ctx.setVariable("ticketNumber", "TKT-88421");
            ctx.setVariable("subject", "Enterprise Webhook Latency Scoping");
            ctx.setVariable("customerName", "Himanshu Bharti");
            ctx.setVariable("customerEmail", recipient);
            ctx.setVariable("description", "Sample test description text verifying enterprise typography and border alignment.");
            ctx.setVariable("priority", "HIGH");
            ctx.setVariable("oldStatus", "IN_PROGRESS");
            ctx.setVariable("newStatus", "RESOLVED");
            ctx.setVariable("statusMessage", "Our engineering team has resolved the issue.");
            ctx.setVariable("agentName", "Senior AI Specialist");
            ctx.setVariable("comment", "All webhook services are operational with response time under 120ms.");
            ctx.setVariable("contactName", "Himanshu Bharti");
            ctx.setVariable("contactEmail", recipient);
            ctx.setVariable("enquiryMessage", "Interested in AI CRM WhatsApp integration.");
            ctx.setVariable("dealLabel", "Annual Enterprise AI CRM Tier");
            ctx.setVariable("dealValue", "150,000");
            ctx.setVariable("currency", "₹");
            ctx.setVariable("score", 94);
            ctx.setVariable("leadName", "Himanshu Bharti");
            ctx.setVariable("title", "Enterprise Solution Architecture Review");
            ctx.setVariable("dateTime", "August 12, 2026 at 3:30 PM IST");
            ctx.setVariable("meetingLink", "https://meet.google.com/gyan-vani-demo");
            ctx.setVariable("service", "Custom AI Flow & Agent Training Session");
            ctx.setVariable("preferredSlot", "August 15, 2026 at 2:00 PM IST");
            ctx.setVariable("role", "Workspace Administrator");
            ctx.setVariable("status", "ACTIVE");
            ctx.setVariable("reason", "Account verified active by system administrator.");
            ctx.setVariable("employeeName", "Himanshu Bharti");
            ctx.setVariable("employeeEmail", recipient);
            ctx.setVariable("body", "<p>Welcome to our marketing broadcast. Enjoy <strong>enterprise features</strong> tailored for your team.</p>");

            if (customVars != null) {
                customVars.forEach(ctx::setVariable);
            }

            emailService.sendTemplate(recipient, "[Test Preview] " + templateName, templateName, ctx);

            return ResponseEntity.ok(Map.of(
                "status", "success",
                "template", templateName,
                "recipient", recipient,
                "message", "Template '" + templateName + "' enqueued to " + recipient
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                "status", "error",
                "template", templateName,
                "message", e.getMessage()
            ));
        }
    }
}
