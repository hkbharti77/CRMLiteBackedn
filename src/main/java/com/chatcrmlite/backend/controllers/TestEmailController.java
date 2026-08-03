package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.services.EmailService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * TEMPORARY test controller for email branding verification.
 * DELETE after testing is complete.
 */
@RestController
@RequestMapping("/api/v1/test-emails")
public class TestEmailController {

    @Autowired
    private EmailService emailService;

    @PostMapping("/send-all")
    public ResponseEntity<?> sendAllTestEmails(@RequestParam String to) {
        try {
            // 1. Lead Enquiry Received (Customer-facing)
            emailService.sendLeadCreatedToContact(
                to, "Himanshu Kumar",
                "GyanVaniAi Corp",
                "I am interested in your AI-powered CRM solution. Please share pricing details."
            );

            // 2. Appointment Confirmation (Customer-facing)
            // Signature: (toEmail, contactName, title, dateTime, ownerBusinessName, meetingLink)
            emailService.sendAppointmentConfirmation(
                to, "Himanshu Kumar",
                "Product Demo - AI CRM Suite",
                "August 5, 2026 at 3:00 PM",
                "GyanVaniAi Corp",
                "https://meet.google.com/abc-defg-hij"
            );

            // 3. Ticket Created (Customer-facing)
            // Signature: (toEmail, customerName, ticketNumber, subject, description, priority)
            emailService.sendTicketCreatedToCustomer(
                to, "Himanshu Kumar",
                "TK-2026-001", "Unable to sync contacts",
                "My contacts are not syncing properly since the last update.",
                "HIGH"
            );

            // 4. Booking Confirmation (Customer-facing)
            // Signature: (toEmail, contactName, service, preferredSlot, ownerBusinessName)
            emailService.sendBookingConfirmation(
                to, "Himanshu Kumar",
                "Premium AI Consultation",
                "August 10, 2026 at 11:00 AM",
                "GyanVaniAi Corp"
            );

            return ResponseEntity.ok(java.util.Map.of(
                "status", "success",
                "message", "4 test emails enqueued to " + to,
                "emails", java.util.List.of(
                    "1. Lead Enquiry Received",
                    "2. Appointment Confirmation",
                    "3. Ticket Created (TK-2026-001)",
                    "4. Booking Confirmation"
                )
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(java.util.Map.of(
                "status", "error",
                "message", e.getMessage()
            ));
        }
    }
}
