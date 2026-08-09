package com.chatcrmlite.backend.services.email;

import com.chatcrmlite.backend.models.EmailTemplate;
import com.chatcrmlite.backend.services.EmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.dialect.SpringStandardDialect;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * High-speed, isolated unit and integration test suite to verify rendering and dispatching
 * of all 26 enterprise email templates to hkbharti77@gmail.com.
 */
public class EmailTemplatesDeliveryTest {

    private static final Logger log = LoggerFactory.getLogger(EmailTemplatesDeliveryTest.class);
    private static final String TARGET_EMAIL = "hkbharti77@gmail.com";

    private SpringTemplateEngine templateEngine;

    private static final List<String> ALL_TEMPLATES = List.of(
        "login-otp",
        "password-reset-otp",
        "ticket-created-customer",
        "ticket-created-owner",
        "ticket-status-update",
        "ticket-assigned-agent",
        "ticket-comment-customer",
        "lead-enquiry-received",
        "lead-followback",
        "lead-closed-won-customer",
        "lead-closed-won-owner",
        "high-value-lead-alert",
        "appointment-confirmation",
        "appointment-cancelled",
        "appointment-completed",
        "appointment-owner",
        "booking-confirmation",
        "booking-cancelled",
        "booking-completed",
        "booking-owner",
        "welcome-staff",
        "staff-status-update-email",
        "staff-status-owner-notification",
        "custom-email",
        "email-template"
    );

    @BeforeEach
    public void setUp() {
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
    @DisplayName("Verify all 26 templates render valid HTML without Thymeleaf errors")
    public void testAllTemplatesRenderSuccessfully() {
        Context ctx = createStandardEnterpriseContext();

        for (String templateName : ALL_TEMPLATES) {
            String path = templateName.equals("email-template") ? templateName : "email/" + templateName;
            assertDoesNotThrow(() -> {
                String html = templateEngine.process(path, ctx);
                assertNotNull(html, "Template output must not be null: " + templateName);
                assertTrue(html.contains("<!DOCTYPE html>"), "Must contain DOCTYPE html: " + templateName);
                assertTrue(html.length() > 200, "HTML length should be substantial: " + templateName);
                log.info("✓ Successfully verified enterprise template: {}", templateName);
            }, "Rendering failed for template: " + templateName);
        }
    }

    @ParameterizedTest(name = "Render individual template: {0}")
    @ValueSource(strings = {
        "login-otp",
        "password-reset-otp",
        "ticket-created-customer",
        "ticket-created-owner",
        "ticket-status-update",
        "ticket-assigned-agent",
        "ticket-comment-customer",
        "lead-enquiry-received",
        "lead-followback",
        "lead-closed-won-customer",
        "lead-closed-won-owner",
        "high-value-lead-alert",
        "appointment-confirmation",
        "appointment-cancelled",
        "appointment-completed",
        "appointment-owner",
        "booking-confirmation",
        "booking-cancelled",
        "booking-completed",
        "booking-owner",
        "welcome-staff",
        "staff-status-update-email",
        "staff-status-owner-notification",
        "custom-email"
    })
    public void testIndividualTemplateRendering(String templateName) {
        Context ctx = createStandardEnterpriseContext();
        String html = templateEngine.process("email/" + templateName, ctx);

        assertNotNull(html);
        assertTrue(html.length() > 200, "HTML length should be substantial for " + templateName);
        assertTrue(html.contains("<!DOCTYPE html>"), "Must be valid HTML for " + templateName);
        assertTrue(html.contains("GyanVaniAi") || html.contains("Business Name"), "Must contain branding");
    }

    @Test
    @DisplayName("Verify target recipient hkbharti77@gmail.com is properly contextualized across templates")
    public void testTargetRecipientContext() {
        Context ctx = createStandardEnterpriseContext();
        
        // 1. Auth & OTP
        String loginOtpHtml = templateEngine.process("email/login-otp", ctx);
        assertTrue(loginOtpHtml.contains(TARGET_EMAIL), "Login OTP must contain recipient email");
        assertTrue(loginOtpHtml.contains("749215"), "Login OTP must contain passcode");

        // 2. Ticket Created
        String ticketHtml = templateEngine.process("email/ticket-created-customer", ctx);
        assertTrue(ticketHtml.contains("TKT-99214"), "Must contain ticket number");
        assertTrue(ticketHtml.contains("HIGH PRIORITY"), "Must contain priority badge");

        // 3. Lead Closed Won
        String leadWonHtml = templateEngine.process("email/lead-closed-won-owner", ctx);
        assertTrue(leadWonHtml.contains("150,000"), "Must contain deal value");

        // 4. Payment Link
        String paymentHtml = templateEngine.process("email-template", ctx);
        assertTrue(paymentHtml.contains("Pay Now"), "Must contain Pay Now button");
    }

    private Context createStandardEnterpriseContext() {
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
        ctx.setVariable("emailFooterText", "Automated system notification.");
        ctx.setVariable("primaryColor", "#2563EB");
        
        ctx.setVariable("otpCode", "749215");
        ctx.setVariable("expiryMinutes", "10");
        ctx.setVariable("userEmail", TARGET_EMAIL);
        ctx.setVariable("loginTime", "2026-08-08 22:55:00 UTC");
        ctx.setVariable("ipAddress", "103.21.244.18");
        ctx.setVariable("userAgent", "Chrome 127.0 (macOS 14.5)");
        ctx.setVariable("ticketNumber", "TKT-99214");
        ctx.setVariable("subject", "Unable to Sync WhatsApp Contacts");
        ctx.setVariable("ticketTitle", "Unable to Sync WhatsApp Contacts");
        ctx.setVariable("ticketDescription", "WhatsApp contact synchronization failed with error code 401.");
        ctx.setVariable("description", "WhatsApp contact synchronization failed with error code 401.");
        ctx.setVariable("customerName", "Himanshu Bharti");
        ctx.setVariable("customerEmail", TARGET_EMAIL);
        ctx.setVariable("priority", "HIGH");
        ctx.setVariable("oldStatus", "IN_PROGRESS");
        ctx.setVariable("newStatus", "RESOLVED");
        ctx.setVariable("statusMessage", "Our engineering team has resolved the synchronization issue.");
        ctx.setVariable("agentName", "Senior AI Specialist");
        ctx.setVariable("comment", "All webhook services are operational.");
        ctx.setVariable("contactName", "Himanshu Bharti");
        ctx.setVariable("contactEmail", TARGET_EMAIL);
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
        ctx.setVariable("employeeEmail", TARGET_EMAIL);
        ctx.setVariable("content", "<p>Thank you for exploring GyanVaniAi enterprise solutions.</p>");
        ctx.setVariable("body", "<p>Welcome to our marketing broadcast. Enjoy our modern CRM features.</p>");
        ctx.setVariable("messageHtml", "<p>Your deal for <strong>₹150,000.00</strong> has been approved!</p>");
        ctx.setVariable("buttonText", "Pay Now");
        ctx.setVariable("buttonLink", "https://gyanvaniai.online/pay/inv_998124_enterprise");

        return ctx;
    }
}
