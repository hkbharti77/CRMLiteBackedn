package com.chatcrmlite.backend.services;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class EmailService {
    private static final Logger log = LoggerFactory.getLogger(EmailService.class);
    /** SecureRandom is thread-safe and cryptographically strong — replaces java.util.Random */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private record OtpEntry(String otp, Instant createdAt) {}

    @Autowired private JavaMailSender mailSender;
    @Autowired private TemplateEngine templateEngine;

    /**
     * SECURITY: Sender email injected from environment — never hardcoded.
     * Set SENDER_EMAIL in your .env or environment variables.
     */
    @Value("${SENDER_EMAIL}")
    private String from;

    private static final String BRAND = "ChatCRM Lite";

    private final Map<String, OtpEntry> otpStorage = new ConcurrentHashMap<>();

    // ── OTP ────────────────────────────────────────────────────────────────

    public void generateAndSendOtp(String toEmail) {
        // SecureRandom — cryptographically strong, not predictable like java.util.Random
        String otp = String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
        otpStorage.put(toEmail, new OtpEntry(otp, Instant.now()));
        sendLoginOtp(toEmail, otp, null, null, null);
    }

    /**
     * Send OTP for login verification using the styled template
     */
    public void sendLoginOtp(String toEmail, String otpCode, String ipAddress, String userAgent, String loginTime) {
        Context ctx = new Context();
        ctx.setVariable("heading", "Login Verification Code");
        ctx.setVariable("greeting", "Hi there,");
        ctx.setVariable("intro", "Someone is trying to log in to your ChatCRM Lite account. Please use the code below to complete your login.");
        ctx.setVariable("footerNote", "This code was generated for a login attempt. If you didn't try to log in, please secure your account.");
        ctx.setVariable("ctaLabel", "Secure Account");
        ctx.setVariable("ctaUrl", "#"); // Could link to account security page
        
        ctx.setVariable("otpCode", otpCode);
        ctx.setVariable("expiryMinutes", "10");
        ctx.setVariable("userEmail", toEmail);
        ctx.setVariable("loginTime", loginTime != null ? loginTime : java.time.LocalDateTime.now().toString());
        ctx.setVariable("ipAddress", ipAddress);
        ctx.setVariable("userAgent", userAgent);
        
        sendTemplate(toEmail, "Your ChatCRM Lite Login Code", "login-otp", ctx);
    }

    /**
     * Send OTP for password reset using the styled template
     */
    public void sendPasswordResetOtp(String toEmail, String otpCode) {
        Context ctx = new Context();
        ctx.setVariable("heading", "Password Reset Code");
        ctx.setVariable("greeting", "Hi,");
        ctx.setVariable("intro", "You requested to reset your password. Please use the verification code below to proceed with resetting your password.");
        ctx.setVariable("footerNote", "If you didn't request a password reset, you can safely ignore this email. Your account remains secure.");
        ctx.setVariable("ctaLabel", "Reset Password");
        ctx.setVariable("ctaUrl", "#"); // Could link to password reset page
        
        ctx.setVariable("otpCode", otpCode);
        ctx.setVariable("expiryMinutes", "10");
        
        sendTemplate(toEmail, "Reset Your ChatCRM Lite Password", "password-reset-otp", ctx);
    }

    /**
     * Generate and send password reset OTP
     */
    public void generateAndSendPasswordResetOtp(String toEmail) {
        String otp = String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
        otpStorage.put(toEmail, new OtpEntry(otp, Instant.now()));
        sendPasswordResetOtp(toEmail, otp);
        // SECURITY: Do NOT log the OTP value — only confirm dispatch
        log.info("[Email] Password reset OTP dispatched (recipient masked)");
    }

    /**
     * Generate and send login OTP with additional context
     */
    public void generateAndSendLoginOtp(String toEmail, String ipAddress, String userAgent) {
        String otp = String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
        otpStorage.put(toEmail, new OtpEntry(otp, Instant.now()));
        String loginTime = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        sendLoginOtp(toEmail, otp, ipAddress, userAgent, loginTime);
        // SECURITY: Do NOT log the OTP value — log IP only for rate-limit investigation
        log.info("[Email] Login OTP dispatched from ip={}", ipAddress);
    }

    // ── Test-Only Helpers ─────────────────────────────────────────────────
    // These methods exist ONLY to support unit/integration tests.
    // They MUST NOT be called from production code paths.
    // They do not send emails and do not bypass any authentication logic.
    // They only inject a known OTP into the in-memory store — the same store
    // that verifyOtp() already reads — so the test verifies the real production logic.

    /**
     * Injects a known OTP with current timestamp for testing valid-OTP scenarios.
     * @VisibleForTesting — do not call from production code.
     */
    public void storeOtpForTesting(String email, String otp) {
        otpStorage.put(email, new OtpEntry(otp, Instant.now()));
    }

    /**
     * Injects a known OTP with a specific past timestamp for testing expiry scenarios.
     * @VisibleForTesting — do not call from production code.
     */
    public void storeExpiredOtpForTesting(String email, String otp, Instant createdAt) {
        otpStorage.put(email, new OtpEntry(otp, createdAt));
    }


    public boolean verifyOtp(String email, String otp) {
        OtpEntry entry = otpStorage.get(email);
        if (entry == null) return false;
        if (Instant.now().isAfter(entry.createdAt().plus(10, ChronoUnit.MINUTES))) {
            otpStorage.remove(email);
            log.warn("[Email] OTP expired and rejected");
            return false;
        }
        if (entry.otp().equals(otp)) {
            otpStorage.remove(email);
            return true;
        }
        return false;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Core send helper — renders Thymeleaf template → HTML → SMTP
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Renders a Thymeleaf template from templates/email/<templateName>.html
     * and sends it as an HTML email.
     *
     * @Async — SMTP dispatch runs on a background thread so it never blocks
     * the calling thread (event listener or service method).
     * Failures are logged but never thrown so a mail error never rolls back
     * a business transaction.
     */
    @Async
    public void sendTemplate(String to, String subject, String templateName, Context ctx) {
        if (to == null || to.isBlank()) {
            log.warn("[Email] Skipping — empty recipient. subject={}", subject);
            return;
        }
        try {
            ctx.setVariable("subject", subject);
            String html = templateEngine.process("email/" + templateName, ctx);
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, "UTF-8");
            // SECURITY: 'from' is injected from env — never hardcoded
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            mailSender.send(mime);
            log.info("[Email] Sent template='{}'", templateName);
        } catch (Exception e) {
            log.error("[Email] Failed template='{}'. Reason: {}", templateName, e.getMessage());
        }
    }

    // ── Ticket Emails ──────────────────────────────────────────────────────

    public void sendTicketCreatedToCustomer(String toEmail, String customerName,
            String ticketNumber, String subject, String description, String priority) {
        Context ctx = new Context();
        ctx.setVariable("heading",    "Support Ticket Received");
        ctx.setVariable("greeting",   "Hi " + customerName + ",");
        ctx.setVariable("intro",      "We have received your support request and created a ticket for you.");
        ctx.setVariable("footerNote", "Our team will respond shortly. You can reply to this email to add more details.");
        ctx.setVariable("ctaLabel",   "View Ticket");
        ctx.setVariable("ctaUrl",     "#");
        ctx.setVariable("customerName",  customerName);
        ctx.setVariable("ticketNumber",  ticketNumber);
        ctx.setVariable("subject",       subject);
        ctx.setVariable("description",   description);
        ctx.setVariable("priority",      priority);
        sendTemplate(toEmail, "[" + BRAND + "] Ticket #" + ticketNumber + " - " + subject,
                "ticket-created-customer", ctx);
    }

    public void sendTicketCreatedToOwner(String ownerEmail, String ownerName,
            String ticketNumber, String subject, String customerName,
            String customerEmail, String priority, String description) {
        Context ctx = new Context();
        ctx.setVariable("heading",       "New Support Ticket");
        ctx.setVariable("greeting",      "Hi " + ownerName + ",");
        ctx.setVariable("intro",         "A new support ticket has been submitted.");
        ctx.setVariable("footerNote",    "Log in to your CRM to review and respond.");
        ctx.setVariable("ctaLabel",      "Open CRM");
        ctx.setVariable("ctaUrl",        "#");
        ctx.setVariable("ownerName",     ownerName);
        ctx.setVariable("ticketNumber",  ticketNumber);
        ctx.setVariable("subject",       subject);
        ctx.setVariable("customerName",  customerName);
        ctx.setVariable("customerEmail", customerEmail);
        ctx.setVariable("priority",      priority);
        ctx.setVariable("description",   description);
        sendTemplate(ownerEmail, "[" + BRAND + "] New Ticket #" + ticketNumber + " from " + customerName,
                "ticket-created-owner", ctx);
    }

    public void sendTicketStatusUpdate(String toEmail, String customerName,
            String ticketNumber, String subject, String oldStatus, String newStatus) {
        Context ctx = new Context();
        ctx.setVariable("heading",       "Ticket Status Updated");
        ctx.setVariable("greeting",      "Hi " + customerName + ",");
        ctx.setVariable("intro",         "Your support ticket status has been updated.");
        ctx.setVariable("footerNote",    statusMessage(newStatus));
        ctx.setVariable("ctaLabel",      "View Ticket");
        ctx.setVariable("ctaUrl",        "#");
        ctx.setVariable("customerName",  customerName);
        ctx.setVariable("ticketNumber",  ticketNumber);
        ctx.setVariable("subject",       subject);
        ctx.setVariable("oldStatus",     oldStatus);
        ctx.setVariable("newStatus",     newStatus);
        ctx.setVariable("statusMessage", statusMessage(newStatus));
        sendTemplate(toEmail, "[" + BRAND + "] Ticket #" + ticketNumber + " - Status: " + newStatus,
                "ticket-status-update", ctx);
    }

    public void sendTicketAssignedToAgent(String agentEmail, String agentName,
            String ticketNumber, String subject, String customerName, String priority) {
        Context ctx = new Context();
        ctx.setVariable("heading",      "Ticket Assigned to You");
        ctx.setVariable("greeting",     "Hi " + agentName + ",");
        ctx.setVariable("intro",        "A support ticket has been assigned to you.");
        ctx.setVariable("footerNote",   "Please review and respond to the customer as soon as possible.");
        ctx.setVariable("ctaLabel",     "Open Ticket");
        ctx.setVariable("ctaUrl",       "#");
        ctx.setVariable("agentName",    agentName);
        ctx.setVariable("ticketNumber", ticketNumber);
        ctx.setVariable("subject",      subject);
        ctx.setVariable("customerName", customerName);
        ctx.setVariable("priority",     priority);
        sendTemplate(agentEmail, "[" + BRAND + "] Ticket #" + ticketNumber + " assigned to you",
                "ticket-assigned-agent", ctx);
    }

    public void sendTicketCommentNotification(String toEmail, String customerName,
            String ticketNumber, String subject, String agentName, String comment) {
        Context ctx = new Context();
        ctx.setVariable("heading",      "New Reply on Your Ticket");
        ctx.setVariable("greeting",     "Hi " + customerName + ",");
        ctx.setVariable("intro",        agentName + " has replied to your support ticket.");
        ctx.setVariable("footerNote",   "You can reply to this email or visit the support portal.");
        ctx.setVariable("ctaLabel",     "View Ticket");
        ctx.setVariable("ctaUrl",       "#");
        ctx.setVariable("customerName", customerName);
        ctx.setVariable("ticketNumber", ticketNumber);
        ctx.setVariable("subject",      subject);
        ctx.setVariable("agentName",    agentName);
        ctx.setVariable("comment",      comment);
        sendTemplate(toEmail, "[" + BRAND + "] Reply on Ticket #" + ticketNumber,
                "ticket-comment-customer", ctx);
    }

    // ── Lead Emails ────────────────────────────────────────────────────────

    public void sendLeadCreatedToContact(String toEmail, String contactName,
            String ownerBusinessName, String enquiryMessage) {
        Context ctx = new Context();
        ctx.setVariable("heading",        "We Received Your Enquiry");
        ctx.setVariable("greeting",       "Hi " + contactName + ",");
        ctx.setVariable("intro",          "Thank you for reaching out. We will be in touch shortly.");
        ctx.setVariable("footerNote",     "Our team will review your enquiry and contact you soon.");
        ctx.setVariable("contactName",    contactName);
        ctx.setVariable("businessName",   ownerBusinessName);
        ctx.setVariable("enquiryMessage", enquiryMessage);
        sendTemplate(toEmail, "We received your enquiry - " + ownerBusinessName,
                "lead-enquiry-received", ctx);
    }

    /** Sent to the owner when a new lead/enquiry arrives. */
    public void sendNewLeadToOwner(String ownerEmail, String ownerName,
            String contactName, String contactEmail,
            String enquiryMessage, String source) {
        Context ctx = new Context();
        ctx.setVariable("heading",        "New Lead / Enquiry Received");
        ctx.setVariable("greeting",       "Hi " + ownerName + ",");
        ctx.setVariable("intro",          "A new enquiry has been submitted via " + source + ".");
        ctx.setVariable("footerNote",     "Log in to your CRM to follow up.");
        ctx.setVariable("ctaLabel",       "Open CRM");
        ctx.setVariable("ctaUrl",         "#");
        ctx.setVariable("ownerName",      ownerName);
        ctx.setVariable("contactName",    contactName);
        ctx.setVariable("customerEmail",  contactEmail);
        ctx.setVariable("description",    enquiryMessage != null ? enquiryMessage : "No message provided.");
        ctx.setVariable("ticketNumber",   "");
        ctx.setVariable("subject",        "New Enquiry from " + contactName);
        ctx.setVariable("priority",       "MEDIUM");
        sendTemplate(ownerEmail, "[" + BRAND + "] New Enquiry from " + contactName,
                "ticket-created-owner", ctx);   // reuses the owner notification layout
    }

    public void sendLeadClosedWon(String toEmail, String contactName,
            String ownerBusinessName, String dealLabel) {
        Context ctx = new Context();
        ctx.setVariable("heading",      "Great News - Deal Confirmed!");
        ctx.setVariable("greeting",     "Hi " + contactName + ",");
        ctx.setVariable("intro",        "Your deal with " + ownerBusinessName + " has been finalised.");
        ctx.setVariable("footerNote",   "Thank you for choosing us. We look forward to working with you!");
        ctx.setVariable("contactName",  contactName);
        ctx.setVariable("businessName", ownerBusinessName);
        ctx.setVariable("dealLabel",    dealLabel);
        sendTemplate(toEmail, "Deal Confirmed - " + ownerBusinessName,
                "lead-closed-won-customer", ctx);
    }

    public void sendLeadClosedWonToOwner(String ownerEmail, String ownerName,
            String contactName, String contactEmail,
            String dealLabel, String dealValue, String currency) {
        Context ctx = new Context();
        ctx.setVariable("heading",      "Lead Closed - Won!");
        ctx.setVariable("greeting",     "Hi " + ownerName + ",");
        ctx.setVariable("intro",        "A lead has been marked as Closed Won.");
        ctx.setVariable("footerNote",   "Congratulations! Log in to update payment status and next steps.");
        ctx.setVariable("ctaLabel",     "Open CRM");
        ctx.setVariable("ctaUrl",       "#");
        ctx.setVariable("ownerName",    ownerName);
        ctx.setVariable("contactName",  contactName);
        ctx.setVariable("contactEmail", contactEmail);
        ctx.setVariable("dealLabel",    dealLabel);
        ctx.setVariable("dealValue",    dealValue);
        ctx.setVariable("currency",     currency);
        sendTemplate(ownerEmail, "[" + BRAND + "] Lead Won - " + contactName,
                "lead-closed-won-owner", ctx);
    }

    // ── Appointment Emails ─────────────────────────────────────────────────

    public void sendAppointmentConfirmation(String toEmail, String contactName,
            String title, String dateTime, String ownerBusinessName, String meetingLink) {
        Context ctx = new Context();
        ctx.setVariable("heading",      "Appointment Confirmed");
        ctx.setVariable("greeting",     "Hi " + contactName + ",");
        ctx.setVariable("intro",        "Your appointment with " + ownerBusinessName + " has been confirmed.");
        ctx.setVariable("footerNote",   "Please add this to your calendar. Contact us if you need to reschedule.");
        ctx.setVariable("ctaLabel",     (meetingLink != null && !meetingLink.isBlank()) ? "Join Meeting" : null);
        ctx.setVariable("ctaUrl",       meetingLink);
        ctx.setVariable("contactName",  contactName);
        ctx.setVariable("title",        title);
        ctx.setVariable("dateTime",     dateTime);
        ctx.setVariable("businessName", ownerBusinessName);
        ctx.setVariable("meetingLink",  meetingLink);
        sendTemplate(toEmail, "Appointment Confirmed - " + title, "appointment-confirmation", ctx);
    }

    public void sendAppointmentCancelled(String toEmail, String contactName,
            String title, String dateTime, String ownerBusinessName) {
        Context ctx = new Context();
        ctx.setVariable("heading",      "Appointment Cancelled");
        ctx.setVariable("greeting",     "Hi " + contactName + ",");
        ctx.setVariable("intro",        "Your appointment with " + ownerBusinessName + " has been cancelled.");
        ctx.setVariable("footerNote",   "Please contact us to reschedule at your convenience.");
        ctx.setVariable("contactName",  contactName);
        ctx.setVariable("title",        title);
        ctx.setVariable("dateTime",     dateTime);
        ctx.setVariable("businessName", ownerBusinessName);
        sendTemplate(toEmail, "Appointment Cancelled - " + title, "appointment-cancelled", ctx);
    }

    public void sendAppointmentCompleted(String toEmail, String contactName,
            String title, String ownerBusinessName) {
        Context ctx = new Context();
        ctx.setVariable("heading",      "Thank You for Your Visit");
        ctx.setVariable("greeting",     "Hi " + contactName + ",");
        ctx.setVariable("intro",        "Your appointment with " + ownerBusinessName + " has been completed.");
        ctx.setVariable("footerNote",   "Thank you for visiting us. We hope to see you again soon!");
        ctx.setVariable("contactName",  contactName);
        ctx.setVariable("title",        title);
        ctx.setVariable("businessName", ownerBusinessName);
        sendTemplate(toEmail, "Thank You - " + title + " Completed", "appointment-completed", ctx);
    }

    public void sendAppointmentCreatedToOwner(String ownerEmail, String ownerName,
            String contactName, String contactEmail, String title, String dateTime) {
        Context ctx = new Context();
        ctx.setVariable("heading",      "New Appointment Scheduled");
        ctx.setVariable("greeting",     "Hi " + ownerName + ",");
        ctx.setVariable("intro",        "A new appointment has been scheduled.");
        ctx.setVariable("footerNote",   "Log in to your CRM to manage this appointment.");
        ctx.setVariable("ctaLabel",     "Open CRM");
        ctx.setVariable("ctaUrl",       "#");
        ctx.setVariable("ownerName",    ownerName);
        ctx.setVariable("contactName",  contactName);
        ctx.setVariable("contactEmail", contactEmail);
        ctx.setVariable("title",        title);
        ctx.setVariable("dateTime",     dateTime);
        sendTemplate(ownerEmail, "[" + BRAND + "] New Appointment - " + title + " with " + contactName,
                "appointment-owner", ctx);
    }

    // ── Booking Emails ─────────────────────────────────────────────────────

    public void sendBookingConfirmation(String toEmail, String contactName,
            String service, String preferredSlot, String ownerBusinessName) {
        Context ctx = new Context();
        ctx.setVariable("heading",       "Booking Confirmed");
        ctx.setVariable("greeting",      "Hi " + contactName + ",");
        ctx.setVariable("intro",         "Your booking with " + ownerBusinessName + " has been confirmed.");
        ctx.setVariable("footerNote",    "We look forward to serving you. Please arrive a few minutes early.");
        ctx.setVariable("contactName",   contactName);
        ctx.setVariable("service",       service);
        ctx.setVariable("preferredSlot", preferredSlot);
        ctx.setVariable("businessName",  ownerBusinessName);
        sendTemplate(toEmail, "Booking Confirmed - " + service, "booking-confirmation", ctx);
    }

    public void sendBookingCancelled(String toEmail, String contactName,
            String service, String preferredSlot, String ownerBusinessName) {
        Context ctx = new Context();
        ctx.setVariable("heading",       "Booking Cancelled");
        ctx.setVariable("greeting",      "Hi " + contactName + ",");
        ctx.setVariable("intro",         "Your booking with " + ownerBusinessName + " has been cancelled.");
        ctx.setVariable("footerNote",    "Please contact us to rebook at a convenient time.");
        ctx.setVariable("contactName",   contactName);
        ctx.setVariable("service",       service);
        ctx.setVariable("preferredSlot", preferredSlot);
        ctx.setVariable("businessName",  ownerBusinessName);
        sendTemplate(toEmail, "Booking Cancelled - " + service, "booking-cancelled", ctx);
    }

    public void sendBookingCompleted(String toEmail, String contactName,
            String service, String ownerBusinessName) {
        Context ctx = new Context();
        ctx.setVariable("heading",      "Thank You for Your Booking");
        ctx.setVariable("greeting",     "Hi " + contactName + ",");
        ctx.setVariable("intro",        "Your booking with " + ownerBusinessName + " has been completed.");
        ctx.setVariable("footerNote",   "Thank you for choosing us. We hope you had a great experience!");
        ctx.setVariable("contactName",  contactName);
        ctx.setVariable("service",      service);
        ctx.setVariable("businessName", ownerBusinessName);
        sendTemplate(toEmail, "Thank You - " + service + " Completed", "booking-completed", ctx);
    }

    public void sendBookingCreatedToOwner(String ownerEmail, String ownerName,
            String contactName, String contactEmail, String service, String preferredSlot) {
        Context ctx = new Context();
        ctx.setVariable("heading",       "New Booking Received");
        ctx.setVariable("greeting",      "Hi " + ownerName + ",");
        ctx.setVariable("intro",         "A new booking has been confirmed.");
        ctx.setVariable("footerNote",    "Log in to your CRM to manage this booking.");
        ctx.setVariable("ctaLabel",      "Open CRM");
        ctx.setVariable("ctaUrl",        "#");
        ctx.setVariable("ownerName",     ownerName);
        ctx.setVariable("contactName",   contactName);
        ctx.setVariable("contactEmail",  contactEmail);
        ctx.setVariable("service",       service);
        ctx.setVariable("preferredSlot", preferredSlot);
        sendTemplate(ownerEmail, "[" + BRAND + "] New Booking - " + service + " from " + contactName,
                "booking-owner", ctx);
    }

    // ── Staff Welcome Emails ──────────────────────────────────────────────────

    public void sendStaffWelcomeEmail(String toEmail, String displayName, String businessName, String role) {
        Context ctx = new Context();
        ctx.setVariable("heading",       "Welcome to the Team!");
        ctx.setVariable("greeting",      "Hi " + displayName + ",");
        ctx.setVariable("intro",         "Your account has been created by your business administrator for " + businessName + ". You have been registered as a " + role + ".");
        ctx.setVariable("footerNote",    "Please use your email address to log in. You will receive a secure 6-digit verification code (OTP) each time you log in.");
        ctx.setVariable("ctaLabel",      "Log In Now");
        ctx.setVariable("ctaUrl",        System.getenv("FRONTEND_URL") != null ? System.getenv("FRONTEND_URL") : "http://localhost:8081"); 
        
        ctx.setVariable("businessName",  businessName);
        ctx.setVariable("role",          role);

        sendTemplate(toEmail, "Welcome to " + businessName + " on " + BRAND,
                "welcome-staff", ctx);
    }

    // ── Staff Status Change Emails ────────────────────────────────────────────

    public void sendStaffStatusChangeEmail(String toEmail, String employeeName, String businessName, boolean isBlocked, String reason) {
        Context ctx = new Context();
        String actionText = isBlocked ? "Blocked" : "Unblocked";
        ctx.setVariable("heading",       "Account " + actionText);
        ctx.setVariable("greeting",      "Hi " + employeeName + ",");
        ctx.setVariable("intro",         "Your staff account for " + businessName + " has been " + actionText.toLowerCase() + " by your administrator.");
        ctx.setVariable("footerNote",    "If you think this is a mistake, please contact your workspace owner.");
        
        ctx.setVariable("reason",        reason != null && !reason.isBlank() ? reason : "No reason specified.");
        ctx.setVariable("businessName",  businessName);
        ctx.setVariable("status",        actionText);

        sendTemplate(toEmail, "Your account has been " + actionText.toLowerCase() + " - " + businessName,
                "staff-status-update-email", ctx);
    }

    public void sendOwnerStaffStatusNotification(String ownerEmail, String ownerName, String employeeName, String employeeEmail, boolean isBlocked, String reason) {
        Context ctx = new Context();
        String actionText = isBlocked ? "Blocked" : "Unblocked";
        ctx.setVariable("heading",       "Staff Account " + actionText);
        ctx.setVariable("greeting",      "Hi " + ownerName + ",");
        ctx.setVariable("intro",         "The staff account for " + employeeName + " (" + employeeEmail + ") has been " + actionText.toLowerCase() + ".");
        ctx.setVariable("footerNote",    "This is an automated notification of user status changes in your workspace.");
        
        ctx.setVariable("reason",        reason != null && !reason.isBlank() ? reason : "No reason specified.");
        ctx.setVariable("employeeName",  employeeName);
        ctx.setVariable("employeeEmail", employeeEmail);
        ctx.setVariable("status",        actionText);

        sendTemplate(ownerEmail, "Notice: Staff account " + actionText.toLowerCase() + " - " + employeeName,
                "staff-status-owner-notification", ctx);
    }


    // ── Helpers ────────────────────────────────────────────────────────────

    private String statusMessage(String status) {
        return switch (status.toUpperCase()) {
            case "IN_PROGRESS"          -> "Our team is actively working on your ticket.";
            case "WAITING_FOR_CUSTOMER" -> "We need more information from you. Please reply to this email.";
            case "RESOLVED"             -> "Your ticket has been resolved. If the issue persists, please let us know.";
            case "CLOSED"               -> "Your ticket has been closed. Thank you for contacting us.";
            default                     -> "Our team will be in touch shortly.";
        };
    }
}
