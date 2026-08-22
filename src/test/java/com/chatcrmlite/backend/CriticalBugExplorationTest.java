package com.chatcrmlite.backend;

import com.chatcrmlite.backend.dto.EnquiryRequest;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.LeadRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.EmailService;
import com.chatcrmlite.backend.services.lead.LeadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;

/**
 * Bug Condition Exploration Tests — Task 1
 *
 * These tests MUST FAIL on unfixed code. Failure confirms each bug exists.
 * After fixes are applied (Tasks 3–5), these same tests must PASS.
 *
 * Bug 1: OTP Has No Expiry         → isBugCondition_OTP
 * Bug 2: Race Condition on Enquiry → isBugCondition_Race
 * Bug 3: waId Unique Constraint    → isBugCondition_Constraint
 */
@SpringBootTest
@ActiveProfiles("test")
public class CriticalBugExplorationTest {

    // ── Bug 1 ──────────────────────────────────────────────────────────────
    @Autowired
    private EmailService emailService;

    @MockBean
    private JavaMailSender javaMailSender;

    // ── Bug 2 ──────────────────────────────────────────────────────────────
    @Autowired
    private LeadService leadService;

    @Autowired
    private LeadRepository leadRepository;

    // ── Bug 3 ──────────────────────────────────────────────────────────────
    @Autowired
    private ContactRepository contactRepository;

    @Autowired
    private UserRepository userRepository;

    private User tenantA;
    private User tenantB;

    @BeforeEach
    void setUp() {
        doNothing().when(javaMailSender).send(any(org.springframework.mail.SimpleMailMessage.class));

        tenantA = userRepository.save(User.builder()
                .email("tenantA_explore_" + UUID.randomUUID() + "@test.com")
                .businessName("Tenant A")
                .build());

        tenantB = userRepository.save(User.builder()
                .email("tenantB_explore_" + UUID.randomUUID() + "@test.com")
                .businessName("Tenant B")
                .build());
    }

    // ══════════════════════════════════════════════════════════════════════
    //  BUG 1 — OTP Has No Expiry
    //  isBugCondition_OTP: (attemptTime − generationTime) > 10 minutes
    //
    //  EXPECTED ON UNFIXED CODE: FAIL (verifyOtp returns true for expired OTP)
    //  EXPECTED AFTER FIX:       PASS (verifyOtp returns false for expired OTP)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Bug1-Exploration: Expired OTP (11 min old) must be rejected — FAILS on unfixed code")
    void bug1_expiredOtpMustBeRejected() {
        String email = "otp_test_" + UUID.randomUUID() + "@test.com";
        String knownOtp = "123456";

        // Inject an OTP that was created 11 minutes ago (already expired)
        // Bug condition: (attemptTime − generationTime) > 10 minutes
        Instant elevenMinutesAgo = Instant.now().minus(11, ChronoUnit.MINUTES);
        emailService.storeExpiredOtpForTesting(email, knownOtp, elevenMinutesAgo);

        // Verify — must return false because OTP is 11 minutes old
        // COUNTEREXAMPLE on unfixed code: returns true (no expiry check exists)
        boolean result = emailService.verifyOtp(email, knownOtp);

        assertThat(result)
                .as("Expired OTP (11 min old) must be rejected — verifyOtp() should return false")
                .isFalse();
    }

    @Test
    @DisplayName("Bug1-Exploration: Very old OTP (24 hours) must be rejected — FAILS on unfixed code")
    void bug1_veryOldOtpMustBeRejected() {
        String email = "otp_old_" + UUID.randomUUID() + "@test.com";
        String knownOtp = "654321";

        Instant twentyFourHoursAgo = Instant.now().minus(24, ChronoUnit.HOURS);
        emailService.storeExpiredOtpForTesting(email, knownOtp, twentyFourHoursAgo);

        boolean result = emailService.verifyOtp(email, knownOtp);

        assertThat(result)
                .as("OTP that is 24 hours old must be rejected")
                .isFalse();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  BUG 2 — Race Condition on Enquiry Updates
    //  isBugCondition_Race: overlap=true AND request1.leadId = request2.leadId
    //
    //  NOTE: @Transactional is NOT on this test class — real concurrency requires
    //  each thread to commit its own transaction. The test uses @DirtiesContext
    //  to clean up after itself.
    //
    //  EXPECTED ON UNFIXED CODE: FAIL (count=1, one enquiry silently dropped)
    //  EXPECTED AFTER FIX:       PASS (count=2, both enquiries persisted)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DirtiesContext
    @DisplayName("Bug2-Exploration: Two concurrent addEnquiry calls must both persist — FAILS on unfixed code")
    void bug2_concurrentEnquiryAddsMustBothPersist() throws Exception {
        // Arrange: create a lead with 0 enquiries (committed immediately)
        Contact contact = contactRepository.saveAndFlush(Contact.builder()
                .waId("race_wa_" + UUID.randomUUID())
                .name("Race Test Contact")
                .source("Test")
                .owner(tenantA)
                .build());

        Lead lead = leadRepository.saveAndFlush(Lead.builder()
                .contact(contact)
                .owner(tenantA)
                .status(Lead.LeadStatus.NEW)
                .enquiries("[]")
                .build());

        UUID leadId = lead.getId();

        EnquiryRequest req1 = new EnquiryRequest();
        req1.setMessage("Concurrent enquiry ONE");
        req1.setType("TEST");
        req1.setSource("Race Test");

        EnquiryRequest req2 = new EnquiryRequest();
        req2.setMessage("Concurrent enquiry TWO");
        req2.setType("TEST");
        req2.setSource("Race Test");

        // Act: fire both addEnquiry calls simultaneously from separate threads
        // Each thread runs in its own transaction (no @Transactional on test class)
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        AtomicInteger failures = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        executor.submit(() -> {
            try {
                startLatch.await();
                leadService.addEnquiry(leadId, req1, tenantA);
            } catch (Exception e) {
                // On unfixed code: last-write-wins, no exception thrown — data silently lost
                // On fixed code: retry succeeds, both enquiries persisted
                System.out.println("--- EXCEPTION IN THREAD 1 ---");
                e.printStackTrace(System.out);
                failures.incrementAndGet();
            } finally {
                doneLatch.countDown();
            }
        });

        executor.submit(() -> {
            try {
                startLatch.await();
                leadService.addEnquiry(leadId, req2, tenantA);
            } catch (Exception e) {
                System.out.println("--- EXCEPTION IN THREAD 2 ---");
                e.printStackTrace(System.out);
                failures.incrementAndGet();
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown(); // release both threads simultaneously
        boolean completed = doneLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue().withFailMessage("Threads did not complete in time");
        assertThat(failures.get()).isEqualTo(0).withFailMessage("One or both threads threw an exception");

        // Assert: both enquiries must be persisted
        // COUNTEREXAMPLE on unfixed code: count=1 (last write wins, one enquiry dropped)
        Lead reloaded = leadRepository.findById(leadId).orElseThrow();
        int enquiryCount = leadService.getEnquiries(reloaded.getId(), tenantA).size();

        assertThat(enquiryCount)
                .as("Both concurrent enquiries must be persisted — expected 2, got %d (last-write-wins bug)", enquiryCount)
                .isEqualTo(2);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  BUG 3 — Contact.waId Unique Constraint Breaks Multi-Tenancy
    //  isBugCondition_Constraint: EXISTS contact WHERE wa_id=X.waId AND owner_id != X.ownerId
    //
    //  EXPECTED ON UNFIXED CODE: FAIL (ConstraintViolationException thrown)
    //  EXPECTED AFTER FIX:       PASS (both contacts created successfully)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @Transactional
    @DisplayName("Bug3-Exploration: Two tenants sharing same waId must both succeed — FAILS on unfixed code")
    void bug3_crossTenantSameWaIdMustBothSucceed() {
        String sharedWaId = "+60123456789_" + UUID.randomUUID();

        // Tenant A creates a contact with this phone number — should always succeed
        Contact contactA = contactRepository.save(Contact.builder()
                .waId(sharedWaId)
                .name("Customer (Tenant A)")
                .source("WhatsApp")
                .owner(tenantA)
                .build());

        assertThat(contactA.getId()).isNotNull();

        // Tenant B tries to create a contact with the SAME phone number
        // COUNTEREXAMPLE on unfixed code: throws ConstraintViolationException
        // because wa_id had a single-column UNIQUE constraint
        Contact contactB = contactRepository.save(Contact.builder()
                .waId(sharedWaId)
                .name("Customer (Tenant B)")
                .source("WhatsApp")
                .owner(tenantB)
                .build());

        assertThat(contactB.getId())
                .as("Tenant B must be able to create a contact with the same waId as Tenant A")
                .isNotNull();

        assertThat(contactB.getOwner().getId())
                .as("Contact B must be scoped to Tenant B")
                .isEqualTo(tenantB.getId());

        // Both contacts must coexist in the database
        assertThat(contactRepository.findByWaIdAndOwner(sharedWaId, tenantA))
                .as("Tenant A's contact must still be retrievable")
                .isPresent();

        assertThat(contactRepository.findByWaIdAndOwner(sharedWaId, tenantB))
                .as("Tenant B's contact must be retrievable")
                .isPresent();
    }
}
