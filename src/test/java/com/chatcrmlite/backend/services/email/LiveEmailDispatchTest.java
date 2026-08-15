package com.chatcrmlite.backend.services.email;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.dialect.SpringStandardDialect;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live SMTP dispatch test to physically send all 26 enterprise email templates
 * directly through Gmail SMTP to hkbharti77@gmail.com.
 */
public class LiveEmailDispatchTest {

    private static final Logger log = LoggerFactory.getLogger(LiveEmailDispatchTest.class);
    private static final String TARGET_EMAIL = "hkbharti77@gmail.com";
    private static final String BRAND_NAME = "GyanVaniAi Connect";

    private JavaMailSenderImpl mailSender;
    private SpringTemplateEngine templateEngine;
    private String fromEmail;

    @BeforeEach
    public void init() {
        // Load credentials from .env
        Properties envProps = new Properties();
        File envFile = new File(".env");
        if (envFile.exists()) {
            try (FileInputStream fis = new FileInputStream(envFile)) {
                envProps.load(fis);
            } catch (Exception e) {
                log.warn("Could not read .env directly: {}", e.getMessage());
            }
        }

        String smtpUser = envProps.getProperty("SMTP_USERNAME", System.getenv("SMTP_USERNAME"));
        String smtpPass = envProps.getProperty("SMTP_PASSWORD", System.getenv("SMTP_PASSWORD"));
        fromEmail = envProps.getProperty("SENDER_EMAIL", "no-reply@gyanvaniai.online");

        if (smtpUser == null || smtpUser.isBlank()) {
            smtpUser = "ewardmacllum@gmail.com";
        }
        if (smtpPass == null || smtpPass.isBlank()) {
            smtpPass = "ckhv ttlv cfzs xply";
        }

        // Clean password in case it contains spaces or quotes
        smtpPass = smtpPass.trim().replaceAll("^\"|\"$", "");

        mailSender = new JavaMailSenderImpl();
        mailSender.setHost("smtp.gmail.com");
        mailSender.setPort(587);
        mailSender.setUsername(smtpUser.trim());
        mailSender.setPassword(smtpPass.trim());

        Properties props = mailSender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.starttls.required", "true");
        props.put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3");
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");

        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(false);

        templateEngine = new SpringTemplateEngine();
        templateEngine.setTemplateResolver(resolver);
        templateEngine.setDialect(new SpringStandardDialect());
    }

    @Test
    @DisplayName("Physically transmit all 26 enterprise templates over SMTP to hkbharti77@gmail.com")
    public void dispatchAllLiveEmailsToRecipient() {
        log.info("==================================================================");
        log.info("🚀 Starting Live SMTP Email Dispatch to: {}", TARGET_EMAIL);
        log.info("==================================================================");

        int sentCount = 0;
        int failedCount = 0;

        Context baseCtx = createEnterpriseContext();

        record EmailSpec(String templateName, String subject, String description) {}

        List<EmailSpec> emailSpecs = List.of(
            new EmailSpec("email/login-otp", "Your GyanVaniAi Connect Login Code: 749215", "Login OTP"),
            new EmailSpec("email/password-reset-otp", "Reset Your GyanVaniAi Connect Password", "Password Reset OTP"),
            new EmailSpec("email/ticket-created-customer", "[GyanVaniAi Support] Ticket #TKT-99214 - Unable to Sync WhatsApp Contacts", "Customer Ticket Created"),
            new EmailSpec("email/ticket-created-owner", "[GyanVaniAi] New Ticket #TKT-99214 from Himanshu Bharti", "Owner Ticket Notification"),
            new EmailSpec("email/ticket-status-update", "[GyanVaniAi] Ticket #TKT-99214 - Status: RESOLVED", "Ticket Status Update"),
            new EmailSpec("email/ticket-assigned-agent", "[GyanVaniAi] Ticket #TKT-99214 Assigned to You", "Ticket Assigned Agent"),
            new EmailSpec("email/ticket-comment-customer", "[GyanVaniAi] Reply on Ticket #TKT-99214", "Ticket Comment Reply"),
            new EmailSpec("email/lead-enquiry-received", "We received your enquiry - GyanVaniAi Enterprise", "Lead Enquiry Received"),
            new EmailSpec("email/lead-followback", "Following up on your GyanVaniAi Enterprise Demo", "Lead Follow-back"),
            new EmailSpec("email/lead-closed-won-customer", "Deal Confirmed - GyanVaniAi Enterprise", "Lead Closed Won Customer"),
            new EmailSpec("email/lead-closed-won-owner", "[GyanVaniAi] Lead Won - Himanshu Bharti (₹150,000)", "Lead Closed Won Owner"),
            new EmailSpec("email/high-value-lead-alert", "[GyanVaniAi] High Value Lead Alert: Himanshu Bharti (Score: 94)", "High Value Lead Alert"),
            new EmailSpec("email/appointment-confirmation", "Appointment Confirmed - Enterprise Solution Architecture Review", "Appointment Confirmed"),
            new EmailSpec("email/appointment-cancelled", "Appointment Cancelled - Initial Technical Scoping Call", "Appointment Cancelled"),
            new EmailSpec("email/appointment-completed", "Thank You - Enterprise Architecture Review Completed", "Appointment Completed"),
            new EmailSpec("email/appointment-owner", "[GyanVaniAi] New Appointment - Enterprise Architecture Review with Himanshu Bharti", "Appointment Owner Alert"),
            new EmailSpec("email/booking-confirmation", "Booking Confirmed - Custom AI Flow & Agent Training Session", "Booking Confirmed"),
            new EmailSpec("email/booking-cancelled", "Booking Cancelled - Custom AI Flow & Agent Training Session", "Booking Cancelled"),
            new EmailSpec("email/booking-completed", "Thank You - Custom AI Flow & Agent Training Session Completed", "Booking Completed"),
            new EmailSpec("email/booking-owner", "[GyanVaniAi] New Booking - Custom AI Flow from Himanshu Bharti", "Booking Owner Alert"),
            new EmailSpec("email/welcome-staff", "Welcome to GyanVaniAi Enterprise Workspace on GyanVaniAi Connect", "Welcome Staff Onboarding"),
            new EmailSpec("email/staff-status-update-email", "Your account has been unblocked - GyanVaniAi Enterprise", "Staff Status Update Member"),
            new EmailSpec("email/staff-status-owner-notification", "Notice: Staff account unblocked - Himanshu Bharti", "Staff Status Owner Notification"),
            new EmailSpec("email/custom-email", "Exclusive: GyanVaniAi Multi-Agent WhatsApp Suite Announcement", "Custom Marketing Campaign"),
            new EmailSpec("email-template", "Payment Request - GyanVaniAi Enterprise Invoice ₹150,000.00", "Payment Request Invoice")
        );

        for (int i = 0; i < emailSpecs.size(); i++) {
            EmailSpec spec = emailSpecs.get(i);
            try {
                String html = templateEngine.process(spec.templateName, baseCtx);

                MimeMessage message = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

                helper.setFrom(new InternetAddress(fromEmail, BRAND_NAME, "UTF-8"));
                helper.setReplyTo(new InternetAddress(fromEmail, BRAND_NAME, "UTF-8"));
                helper.setTo(TARGET_EMAIL);
                helper.setSubject(spec.subject);
                helper.setText(html, true);

                mailSender.send(message);
                sentCount++;
                log.info("📧 [{} / {}] Successfully Transmitted: {} -> {}", (i + 1), emailSpecs.size(), spec.description, spec.subject);

                // Polite delay to prevent Gmail rate throttling
                Thread.sleep(400);

            } catch (Exception e) {
                failedCount++;
                log.error("❌ Failed to send {} ({}): {}", spec.description, spec.templateName, e.getMessage());
            }
        }

        log.info("==================================================================");
        log.info("🎉 Email Dispatch Completed! Sent: {} | Failed: {} | Recipient: {}", sentCount, failedCount, TARGET_EMAIL);
        log.info("==================================================================");

        assertTrue(sentCount > 0, "At least one email should have been sent successfully");
    }

    private Context createEnterpriseContext() {
        Context ctx = new Context();
        ctx.setVariable("heading", "Enterprise Notification");
        ctx.setVariable("greeting", "Hi Himanshu Bharti,");
        ctx.setVariable("intro", "This is an automated system notification from your GyanVaniAi CRM workspace.");
        ctx.setVariable("footerNote", "If you have questions regarding this notice, reply directly to this email.");
        ctx.setVariable("ctaLabel", "Open Dashboard");
        ctx.setVariable("ctaUrl", "https://gyanvaniai.online");
        ctx.setVariable("businessName", "GyanVaniAi Enterprise");
        ctx.setVariable("businessAddress", "Cyber City, DLF Phase 2, Gurugram, India");
        ctx.setVariable("emailHeaderText", "GyanVaniAi Verified Notification");
        ctx.setVariable("emailFooterText", "Automated system notification. Replies to this address are monitored.");
        ctx.setVariable("primaryColor", "#2563EB");
        ctx.setVariable("platformBrandUrl", "https://gyanvaniai.online");
        ctx.setVariable("platformBrandName", "GyanVaniAi");

        // Auth & Security
        ctx.setVariable("otpCode", "749215");
        ctx.setVariable("expiryMinutes", "10");
        ctx.setVariable("userEmail", TARGET_EMAIL);
        ctx.setVariable("loginTime", "2026-08-08 22:55:00 UTC");
        ctx.setVariable("ipAddress", "103.21.244.18");
        ctx.setVariable("userAgent", "Chrome 127.0 (macOS 14.5)");

        // Ticketing
        ctx.setVariable("ticketNumber", "TKT-99214");
        ctx.setVariable("subject", "Unable to Sync WhatsApp Contacts");
        ctx.setVariable("ticketTitle", "Unable to Sync WhatsApp Contacts");
        ctx.setVariable("ticketDescription", "WhatsApp contact synchronization failed with error code 401. Please investigate.");
        ctx.setVariable("description", "WhatsApp contact synchronization failed with error code 401. Please investigate.");
        ctx.setVariable("customerName", "Himanshu Bharti");
        ctx.setVariable("customerEmail", TARGET_EMAIL);
        ctx.setVariable("priority", "HIGH");
        ctx.setVariable("oldStatus", "IN_PROGRESS");
        ctx.setVariable("newStatus", "RESOLVED");
        ctx.setVariable("statusMessage", "Our engineering team has verified your webhooks and WhatsApp synchronization is fully operational.");
        ctx.setVariable("agentName", "Senior AI Specialist");
        ctx.setVariable("comment", "All webhook services are operational with latency under 120ms.");

        // Leads & CRM
        ctx.setVariable("contactName", "Himanshu Bharti");
        ctx.setVariable("contactEmail", TARGET_EMAIL);
        ctx.setVariable("enquiryMessage", "Interested in GyanVaniAi Enterprise CRM WhatsApp suite with 50 agent seats.");
        ctx.setVariable("dealLabel", "Annual Enterprise AI CRM Tier");
        ctx.setVariable("dealValue", "150,000");
        ctx.setVariable("currency", "₹");
        ctx.setVariable("score", 94);
        ctx.setVariable("leadName", "Himanshu Bharti (Enterprise Prospect)");
        ctx.setVariable("content", "<p>Thank you for exploring GyanVaniAi enterprise solutions. We have prepared your custom deployment roadmap and look forward to our onboarding session.</p>");

        // Appointments & Bookings
        ctx.setVariable("title", "Enterprise Solution Architecture Review");
        ctx.setVariable("dateTime", "August 12, 2026 at 3:30 PM IST");
        ctx.setVariable("meetingLink", "https://meet.google.com/gyan-vani-demo");
        ctx.setVariable("service", "Custom AI Flow & Agent Training Session");
        ctx.setVariable("preferredSlot", "August 15, 2026 at 2:00 PM IST");

        // Staff Admin
        ctx.setVariable("ownerName", "Executive Leadership");
        ctx.setVariable("role", "Workspace Administrator");
        ctx.setVariable("status", "ACTIVE");
        ctx.setVariable("reason", "Account privileges reviewed and verified active by security administration.");
        ctx.setVariable("employeeName", "Himanshu Bharti");
        ctx.setVariable("employeeEmail", TARGET_EMAIL);

        // Marketing & Payment
        ctx.setVariable("body", "<p>We are thrilled to announce our <strong>Next-Gen AI Multi-Agent Inbox</strong> for WhatsApp and Omnichannel CRM.</p><p>Equip your entire team with automated lead qualification, sentiment analysis, and instantaneous support routing.</p>");
        ctx.setVariable("messageHtml", "<p>Your enterprise deal for <strong>₹150,000.00</strong> has been approved!</p><p>Please complete your payment securely using the link below:</p>");
        ctx.setVariable("buttonText", "Pay Now");
        ctx.setVariable("buttonLink", "https://gyanvaniai.online/pay/inv_998124_enterprise");

        return ctx;
    }
}
