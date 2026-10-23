package com.chatcrmlite.backend.config;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.LeadRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.math.BigDecimal;
import com.chatcrmlite.backend.models.SubscriptionPlan;
import com.chatcrmlite.backend.repositories.SubscriptionPlanRepository;
import com.chatcrmlite.backend.models.PlatformAdmin;
import com.chatcrmlite.backend.repositories.PlatformAdminRepository;

@Component
public class DataInitializer implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ContactRepository contactRepository;

    @Autowired
    private LeadRepository leadRepository;

    @Autowired
    private com.chatcrmlite.backend.repositories.MessageRepository messageRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private SubscriptionPlanRepository subscriptionPlanRepository;

    @Autowired
    private PlatformAdminRepository platformAdminRepository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Override
    public void run(String... args) throws Exception {
        try {
            jdbcTemplate.execute("ALTER TABLE contacts ADD COLUMN IF NOT EXISTS escalated BOOLEAN DEFAULT FALSE;");
            jdbcTemplate.execute("ALTER TABLE contacts ADD COLUMN IF NOT EXISTS escalated_at TIMESTAMP;");
            jdbcTemplate.execute("ALTER TABLE contacts ADD COLUMN IF NOT EXISTS latest_sentiment VARCHAR(255) DEFAULT 'NEUTRAL';");
            jdbcTemplate.execute("ALTER TABLE contacts ADD COLUMN IF NOT EXISTS support_state VARCHAR(20) DEFAULT 'IDLE';");
            jdbcTemplate.execute("ALTER TABLE contacts ADD COLUMN IF NOT EXISTS assigned_agent_id UUID;");
            jdbcTemplate.execute("ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS sentiment VARCHAR(255) DEFAULT 'NEUTRAL';");
            jdbcTemplate.execute("ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS sentiment_score DOUBLE PRECISION DEFAULT 0.0;");
            jdbcTemplate.execute("ALTER TABLE leads ADD COLUMN IF NOT EXISTS score_grade VARCHAR(255) DEFAULT 'COLD';");
            jdbcTemplate.execute("ALTER TABLE leads ADD COLUMN IF NOT EXISTS last_scored_at TIMESTAMP;");
            jdbcTemplate.execute("ALTER TABLE app_users ADD COLUMN IF NOT EXISTS availability_status VARCHAR(255) DEFAULT 'AVAILABLE';");
            jdbcTemplate.execute("ALTER TABLE app_users ADD COLUMN IF NOT EXISTS max_concurrent_chats INT DEFAULT 2;");
            jdbcTemplate.execute("ALTER TABLE app_users ADD COLUMN IF NOT EXISTS last_seen_at TIMESTAMP;");
            jdbcTemplate.execute("ALTER TABLE billing_transactions ADD COLUMN IF NOT EXISTS invoice_email_status VARCHAR(20) DEFAULT 'PENDING';");
            jdbcTemplate.execute("ALTER TABLE billing_transactions ADD COLUMN IF NOT EXISTS invoice_email_sent_at TIMESTAMP;");
        } catch (Exception e) {
            log.warn("[SchemaMigration] Column auto-migration notice: {}", e.getMessage());
        }

        seedSubscriptionPlans();

        // --- CLEANUP MOCK DATA ---
        List<String> mockNames = Arrays.asList("John Doe", "Sarah Smith");
        for (String name : mockNames) {
            List<Contact> mockContacts = contactRepository.findByName(name);
            for (Contact contact : mockContacts) {
                // Delete associated leads
                List<Lead> leads = leadRepository.findAllByContact(contact);
                leadRepository.deleteAll(leads);
                
                // Delete associated messages
                var messages = messageRepository.findAllByContactOrderByTimestampAsc(contact);
                messageRepository.deleteAll(messages);
                
                // Delete contact
                contactRepository.delete(contact);
                log.info("[Init] Purged mock contact: {}", name);
            }
        }

        // System is initialized with an empty database to ensure production-like state.
        // Users should register through the Auth flow.
        if (userRepository.count() == 0) {
            log.info("[Init] No users found. Ready for new registrations.");
        }

        seedPlatformAdmin();
    }

    private void seedPlatformAdmin() {
        if (platformAdminRepository.count() == 0) {
            PlatformAdmin admin = new PlatformAdmin();
            admin.setEmail("gyanvaniai@gmail.com");
            admin.setPasswordHash("OTP_ONLY");
            admin.setDisplayName("Platform Owner");
            platformAdminRepository.save(admin);
            log.info("[Init] Seeded Platform Admin: gyanvaniai@gmail.com (OTP based login)");
        } else {
            // Ensure email is set to gyanvaniai@gmail.com if it already exists
            PlatformAdmin admin = platformAdminRepository.findAll().get(0);
            if (!"gyanvaniai@gmail.com".equals(admin.getEmail())) {
                admin.setEmail("gyanvaniai@gmail.com");
                admin.setPasswordHash("OTP_ONLY");
                platformAdminRepository.save(admin);
                log.info("[Init] Updated Platform Admin email to gyanvaniai@gmail.com");
            }
        }
    }

    private void seedSubscriptionPlans() {
        if (subscriptionPlanRepository.findById("FREE").isEmpty()) {
            subscriptionPlanRepository.save(new SubscriptionPlan(
                "FREE", "Free Starter Pack", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                1, 100, 15, 10, 500, false, false, 0, false, false
            ));
            log.info("[Init] Seeded FREE subscription plan.");
        }
        if (subscriptionPlanRepository.findById("STARTER").isEmpty()) {
            SubscriptionPlan starter = SubscriptionPlan.builder()
                .id("STARTER")
                .name("Starter Plan")
                .priceMonthly(new BigDecimal("1999.00"))
                .priceYearly(new BigDecimal("19190.00"))
                .priceMonthlyInr(new BigDecimal("1999.00"))
                .priceYearlyInr(new BigDecimal("19190.00"))
                .priceMonthlyUsd(new BigDecimal("24.99"))
                .priceYearlyUsd(new BigDecimal("239.90"))
                .employeeLimit(3)
                .primaryResourceLimit(5000)
                .secondaryResourceLimit(1000)
                .ticketLimit(1000)
                .emailLimit(5000)
                .hasWhatsapp(true)
                .hasWhatsappCampaign(true)
                .whatsappCampaignLimit(2500)
                .hasCustomWidget(false)
                .hasRagLlm(false)
                .includedAiCredits(2500)
                .hasWhatsappCoexistence(true)
                .build();
            subscriptionPlanRepository.save(starter);
            log.info("[Init] Seeded STARTER subscription plan.");
        }
        if (subscriptionPlanRepository.findById("GROWTH").isEmpty()) {
            SubscriptionPlan growth = SubscriptionPlan.builder()
                .id("GROWTH")
                .name("Growth Plan")
                .priceMonthly(new BigDecimal("4999.00"))
                .priceYearly(new BigDecimal("47990.00"))
                .priceMonthlyInr(new BigDecimal("4999.00"))
                .priceYearlyInr(new BigDecimal("47990.00"))
                .priceMonthlyUsd(new BigDecimal("59.99"))
                .priceYearlyUsd(new BigDecimal("575.90"))
                .employeeLimit(8)
                .primaryResourceLimit(20000)
                .secondaryResourceLimit(10000)
                .ticketLimit(10000)
                .emailLimit(25000)
                .hasWhatsapp(true)
                .hasWhatsappCampaign(true)
                .whatsappCampaignLimit(25000)
                .hasCustomWidget(true)
                .hasRagLlm(true)
                .includedAiCredits(10000)
                .hasVoiceBot(true)
                .hasWhatsappCoexistence(true)
                .build();
            subscriptionPlanRepository.save(growth);
            log.info("[Init] Seeded GROWTH subscription plan.");
        }
        if (subscriptionPlanRepository.findById("SCALE").isEmpty()) {
            SubscriptionPlan scale = SubscriptionPlan.builder()
                .id("SCALE")
                .name("Scale Plan")
                .priceMonthly(new BigDecimal("9999.00"))
                .priceYearly(new BigDecimal("95990.00"))
                .priceMonthlyInr(new BigDecimal("9999.00"))
                .priceYearlyInr(new BigDecimal("95990.00"))
                .priceMonthlyUsd(new BigDecimal("119.99"))
                .priceYearlyUsd(new BigDecimal("1151.90"))
                .employeeLimit(20)
                .primaryResourceLimit(100000)
                .secondaryResourceLimit(50000)
                .ticketLimit(50000)
                .emailLimit(100000)
                .hasWhatsapp(true)
                .hasWhatsappCampaign(true)
                .whatsappCampaignLimit(100000)
                .hasCustomWidget(true)
                .hasRagLlm(true)
                .includedAiCredits(25000)
                .hasVoiceBot(true)
                .hasCallingAgent(true)
                .hasWhatsappCoexistence(true)
                .build();
            subscriptionPlanRepository.save(scale);
            log.info("[Init] Seeded SCALE subscription plan.");
        }
        if (subscriptionPlanRepository.findById("ENTERPRISE").isEmpty()) {
            SubscriptionPlan enterprise = new SubscriptionPlan(
                "ENTERPRISE", "Enterprise Custom", new BigDecimal("19999.00"), new BigDecimal("191990.00"), new BigDecimal("249.99"), new BigDecimal("2399.90"),
                50, 1000000, 1000000, 1000000, 1000000, true, true, 1000000, true, true
            );
            enterprise.setContactUs(true);
            enterprise.setIncludedAiCredits(100000);
            enterprise.setHasVoiceBot(true);
            enterprise.setHasCallingAgent(true);
            subscriptionPlanRepository.save(enterprise);
            log.info("[Init] Seeded ENTERPRISE subscription plan.");
        }
    }
}
