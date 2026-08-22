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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;

/**
 * Preservation Tests — Task 2
 *
 * These tests cover inputs where isBugCondition returns FALSE for each bug.
 * They MUST PASS on UNFIXED code (baseline) and MUST CONTINUE TO PASS after fixes.
 *
 * Bug 1: Valid OTP within window, wrong OTP, one-time use
 * Bug 2: Single sequential enquiry add/update/delete
 * Bug 3: Same-tenant duplicate waId rejection, findByWaIdAndOwner correctness
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class CriticalBugPreservationTest {

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

    private User owner;

    @BeforeEach
    void setUp() {
        doNothing().when(javaMailSender).send(any(org.springframework.mail.SimpleMailMessage.class));

        owner = userRepository.save(User.builder()
                .email("preserve_owner_" + UUID.randomUUID() + "@test.com")
                .businessName("Preservation Test Business")
                .build());
    }

    // ══════════════════════════════════════════════════════════════════════
    //  BUG 1 PRESERVATION — Valid OTP Behavior Unchanged
    //  Non-bug-condition: elapsed time ≤ 10 minutes
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Preserve-Bug1: Correct OTP submitted immediately must be accepted")
    void preserve_bug1_correctOtpImmediatelyAccepted() {
        String email = "preserve_otp_" + UUID.randomUUID() + "@test.com";
        String knownOtp = "999888";

        // Inject a fresh OTP (created now — within the 10-minute window)
        emailService.storeOtpForTesting(email, knownOtp);

        boolean result = emailService.verifyOtp(email, knownOtp);

        assertThat(result)
                .as("Correct OTP submitted immediately must be accepted")
                .isTrue();
    }

    @Test
    @DisplayName("Preserve-Bug1: Wrong OTP must always be rejected regardless of timing")
    void preserve_bug1_wrongOtpAlwaysRejected() {
        String email = "preserve_wrong_" + UUID.randomUUID() + "@test.com";
        emailService.storeOtpForTesting(email, "111222");

        boolean result = emailService.verifyOtp(email, "000000_wrong");

        assertThat(result)
                .as("Wrong OTP must be rejected")
                .isFalse();
    }

    @Test
    @DisplayName("Preserve-Bug1: OTP must be one-time use — second submission must fail")
    void preserve_bug1_otpIsOneTimeUse() {
        String email = "preserve_onetime_" + UUID.randomUUID() + "@test.com";
        String knownOtp = "777666";

        emailService.storeOtpForTesting(email, knownOtp);

        // First use — must succeed
        boolean firstResult = emailService.verifyOtp(email, knownOtp);
        assertThat(firstResult).as("First OTP submission must succeed").isTrue();

        // Second use of the same OTP — must fail (one-time use)
        boolean secondResult = emailService.verifyOtp(email, knownOtp);
        assertThat(secondResult)
                .as("Second submission of the same OTP must be rejected (one-time use)")
                .isFalse();
    }

    @Test
    @DisplayName("Preserve-Bug1: Unknown email OTP verification must return false")
    void preserve_bug1_unknownEmailReturnsFalse() {
        boolean result = emailService.verifyOtp("nobody_" + UUID.randomUUID() + "@test.com", "123456");
        assertThat(result).as("Unknown email must return false").isFalse();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  BUG 2 PRESERVATION — Single Sequential Enquiry Writes Unchanged
    //  Non-bug-condition: single (non-concurrent) writes
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Preserve-Bug2: Single addEnquiry must increase count by exactly 1")
    void preserve_bug2_singleAddEnquiryIncreasesCountByOne() {
        Lead lead = createTestLead();
        int initialCount = leadService.getEnquiries(lead.getId(), owner).size();

        EnquiryRequest req = new EnquiryRequest();
        req.setMessage("Single sequential enquiry");
        req.setType("MANUAL");
        req.setSource("Preservation Test");

        leadService.addEnquiry(lead.getId(), req, owner);

        Lead reloaded = leadRepository.findById(lead.getId()).orElseThrow();
        int finalCount = leadService.getEnquiries(reloaded.getId(), owner).size();

        assertThat(finalCount)
                .as("Single addEnquiry must increase count by exactly 1")
                .isEqualTo(initialCount + 1);
    }

    @Test
    @DisplayName("Preserve-Bug2: updateEnquiry must modify only the target enquiry")
    void preserve_bug2_updateEnquiryModifiesOnlyTarget() {
        Lead lead = createTestLead();

        EnquiryRequest req1 = new EnquiryRequest();
        req1.setMessage("First enquiry");
        req1.setType("MANUAL");
        req1.setSource("Test");
        leadService.addEnquiry(lead.getId(), req1, owner);

        EnquiryRequest req2 = new EnquiryRequest();
        req2.setMessage("Second enquiry");
        req2.setType("MANUAL");
        req2.setSource("Test");
        leadService.addEnquiry(lead.getId(), req2, owner);

        Lead afterAdds = leadRepository.findById(lead.getId()).orElseThrow();
        var enquiries = leadService.getEnquiries(afterAdds.getId(), owner);
        assertThat(enquiries).hasSize(2);

        String targetId = enquiries.get(0).getId();
        String originalSecondMessage = enquiries.get(1).getMessage();

        EnquiryRequest updateReq = new EnquiryRequest();
        updateReq.setMessage("Updated first enquiry");
        leadService.updateEnquiry(lead.getId(), targetId, updateReq, owner);

        Lead afterUpdate = leadRepository.findById(lead.getId()).orElseThrow();
        var updatedEnquiries = leadService.getEnquiries(afterUpdate.getId(), owner);

        assertThat(updatedEnquiries).hasSize(2);

        var updated = updatedEnquiries.stream()
                .filter(e -> targetId.equals(e.getId()))
                .findFirst().orElseThrow();
        assertThat(updated.getMessage())
                .as("Target enquiry message must be updated")
                .isEqualTo("Updated first enquiry");

        var untouched = updatedEnquiries.stream()
                .filter(e -> !targetId.equals(e.getId()))
                .findFirst().orElseThrow();
        assertThat(untouched.getMessage())
                .as("Non-target enquiry must remain unchanged")
                .isEqualTo(originalSecondMessage);
    }

    @Test
    @DisplayName("Preserve-Bug2: deleteEnquiry must remove only the target and decrease count by 1")
    void preserve_bug2_deleteEnquiryRemovesOnlyTarget() {
        Lead lead = createTestLead();

        EnquiryRequest req1 = new EnquiryRequest();
        req1.setMessage("Keep this one");
        req1.setType("MANUAL");
        req1.setSource("Test");
        leadService.addEnquiry(lead.getId(), req1, owner);

        EnquiryRequest req2 = new EnquiryRequest();
        req2.setMessage("Delete this one");
        req2.setType("MANUAL");
        req2.setSource("Test");
        leadService.addEnquiry(lead.getId(), req2, owner);

        Lead afterAdds = leadRepository.findById(lead.getId()).orElseThrow();
        var enquiries = leadService.getEnquiries(afterAdds.getId(), owner);
        assertThat(enquiries).hasSize(2);

        String deleteId = enquiries.stream()
                .filter(e -> "Delete this one".equals(e.getMessage()))
                .findFirst().orElseThrow().getId();

        leadService.deleteEnquiry(lead.getId(), deleteId, owner);

        Lead afterDelete = leadRepository.findById(lead.getId()).orElseThrow();
        var remaining = leadService.getEnquiries(afterDelete.getId(), owner);

        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).getMessage())
                .as("The non-deleted enquiry must remain")
                .isEqualTo("Keep this one");
        assertThat(remaining.stream().anyMatch(e -> deleteId.equals(e.getId())))
                .as("Deleted enquiry must not be present")
                .isFalse();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  BUG 3 PRESERVATION — Same-Tenant Uniqueness and Lookups Unchanged
    //  Non-bug-condition: no cross-tenant waId collision
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Preserve-Bug3: Same-tenant duplicate waId must still be rejected")
    void preserve_bug3_sameTenantDuplicateWaIdRejected() {
        String waId = "+60987654321_preserve_" + UUID.randomUUID();

        // First contact — must succeed
        contactRepository.save(Contact.builder()
                .waId(waId)
                .name("First Contact")
                .source("Test")
                .owner(owner)
                .build());

        // Second contact with same waId for same tenant — must be rejected
        assertThatThrownBy(() -> {
            contactRepository.saveAndFlush(Contact.builder()
                    .waId(waId)
                    .name("Duplicate Contact")
                    .source("Test")
                    .owner(owner)
                    .build());
        })
                .as("Same-tenant duplicate waId must throw a constraint violation")
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("Preserve-Bug3: findByWaIdAndOwner must return the correct tenant-scoped contact")
    void preserve_bug3_findByWaIdAndOwnerReturnsTenantScopedContact() {
        String waId = "+60111222333_preserve_" + UUID.randomUUID();

        Contact saved = contactRepository.save(Contact.builder()
                .waId(waId)
                .name("Scoped Contact")
                .source("WhatsApp")
                .owner(owner)
                .build());

        var found = contactRepository.findByWaIdAndOwner(waId, owner);

        assertThat(found)
                .as("findByWaIdAndOwner must return the contact")
                .isPresent();

        assertThat(found.get().getId())
                .as("Returned contact must match the saved one")
                .isEqualTo(saved.getId());

        assertThat(found.get().getOwner().getId())
                .as("Returned contact must be scoped to the correct owner")
                .isEqualTo(owner.getId());
    }

    @Test
    @DisplayName("Preserve-Bug3: findByWaIdAndOwner with wrong owner must return empty")
    void preserve_bug3_findByWaIdAndOwnerWithWrongOwnerReturnsEmpty() {
        String waId = "+60444555666_preserve_" + UUID.randomUUID();

        contactRepository.save(Contact.builder()
                .waId(waId)
                .name("Owner A Contact")
                .source("WhatsApp")
                .owner(owner)
                .build());

        User otherOwner = userRepository.save(User.builder()
                .email("other_preserve_" + UUID.randomUUID() + "@test.com")
                .businessName("Other Business")
                .build());

        var found = contactRepository.findByWaIdAndOwner(waId, otherOwner);

        assertThat(found)
                .as("findByWaIdAndOwner with wrong owner must return empty")
                .isEmpty();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════════════════════════

    private Lead createTestLead() {
        Contact contact = contactRepository.save(Contact.builder()
                .waId("preserve_wa_" + UUID.randomUUID())
                .name("Preservation Contact")
                .source("Test")
                .owner(owner)
                .build());

        return leadRepository.save(Lead.builder()
                .contact(contact)
                .owner(owner)
                .status(Lead.LeadStatus.NEW)
                .enquiries("[]")
                .build());
    }
}
