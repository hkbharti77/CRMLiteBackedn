package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.LeadRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Edge case tests for Lead Service with multiple leads per contact functionality.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class LeadServiceEdgeCaseTest {

    @Autowired
    private LeadService leadService;

    @Autowired
    private LeadRepository leadRepository;

    @Autowired
    private ContactRepository contactRepository;

    @Autowired
    private UserRepository userRepository;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .email("test@example.com")
                .password("password")
                .businessName("Test Business")
                .businessSubType("RESTAURANT")
                .build();
        testUser = userRepository.save(testUser);
    }

    // ── Test 6.1: Contact with no leads ───────────────────────────────────

    @Test
    void testContactWithNoLeads() {
        // Given: A contact with no leads
        Contact contact = createTestContact("No Leads Contact", "919876543210");

        // When: Getting leads for the contact
        List<Lead> leads = leadService.getLeadsByContactId(contact.getId(), testUser);

        // Then: Empty list is returned
        assertThat(leads).isEmpty();

        // And: Active lead count is zero
        long activeCount = leadService.getActiveLeadCountByContactId(contact.getId(), testUser);
        assertThat(activeCount).isEqualTo(0);

        // And: Getting latest lead throws exception
        assertThatThrownBy(() -> leadService.getLatestLeadByContactId(contact.getId(), testUser))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("No lead found for this contact");
    }

    // ── Test 6.2: Contact with only closed leads ──────────────────────────

    @Test
    void testContactWithOnlyClosedLeads() {
        // Given: A contact with only closed leads
        Contact contact = createTestContact("Closed Leads Contact", "919876543211");
        Lead closedWonLead = createTestLead(contact, Lead.LeadStatus.CLOSED_WON);
        Lead closedLostLead = createTestLead(contact, Lead.LeadStatus.CLOSED_LOST);

        // When: Getting all leads
        List<Lead> allLeads = leadService.getLeadsByContactId(contact.getId(), testUser);

        // Then: All leads are returned
        assertThat(allLeads).hasSize(2);
        assertThat(allLeads).extracting(Lead::getStatus)
                .containsExactlyInAnyOrder(Lead.LeadStatus.CLOSED_WON, Lead.LeadStatus.CLOSED_LOST);

        // And: Active lead count is zero
        long activeCount = leadService.getActiveLeadCountByContactId(contact.getId(), testUser);
        assertThat(activeCount).isEqualTo(0);

        // And: Latest lead is the most recently created
        Lead latestLead = leadService.getLatestLeadByContactId(contact.getId(), testUser);
        assertThat(latestLead.getId()).isIn(closedWonLead.getId(), closedLostLead.getId());
    }

    // ── Test 6.3: Contact with multiple active leads ──────────────────────

    @Test
    void testContactWithMultipleActiveLeads() {
        // Given: A contact with multiple active leads
        Contact contact = createTestContact("Multiple Active Contact", "919876543212");
        Lead newLead = createTestLead(contact, Lead.LeadStatus.NEW);
        Lead interestedLead = createTestLead(contact, Lead.LeadStatus.INTERESTED);
        Lead followUpLead = createTestLead(contact, Lead.LeadStatus.FOLLOW_UP);
        Lead bookedLead = createTestLead(contact, Lead.LeadStatus.BOOKED);

        // When: Getting active lead count
        long activeCount = leadService.getActiveLeadCountByContactId(contact.getId(), testUser);

        // Then: All non-closed leads are counted as active
        assertThat(activeCount).isEqualTo(4);

        // And: All leads are retrievable
        List<Lead> allLeads = leadService.getLeadsByContactId(contact.getId(), testUser);
        assertThat(allLeads).hasSize(4);
        assertThat(allLeads).extracting(Lead::getStatus)
                .containsExactlyInAnyOrder(
                        Lead.LeadStatus.NEW,
                        Lead.LeadStatus.INTERESTED,
                        Lead.LeadStatus.FOLLOW_UP,
                        Lead.LeadStatus.BOOKED
                );
    }

    // ── Test 6.4: Lead creation during high concurrency ───────────────────

    @Test
    void testLeadCreationDuringHighConcurrency() throws Exception {
        // Given: A contact and multiple concurrent lead creation attempts
        Contact contact = createTestContact("Concurrent Contact", "919876543213");
        ExecutorService executor = Executors.newFixedThreadPool(10);

        try {
            // When: Creating leads concurrently
            List<CompletableFuture<Lead>> futures = IntStream.range(0, 20)
                    .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                        Lead lead = Lead.builder()
                                .contact(contact)
                                .owner(testUser)
                                .status(Lead.LeadStatus.NEW)
                                .build();
                        return leadRepository.save(lead);
                    }, executor))
                    .toList();

            // Wait for all to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            // Then: All leads should be created successfully
            List<Lead> allLeads = leadService.getLeadsByContactId(contact.getId(), testUser);
            assertThat(allLeads).hasSize(20);
            assertThat(allLeads).allMatch(lead -> lead.getContact().getId().equals(contact.getId()));
            assertThat(allLeads).allMatch(lead -> lead.getOwner().getId().equals(testUser.getId()));

        } finally {
            executor.shutdown();
        }
    }

    // ── Test 6.5: Database constraint violations ──────────────────────────

    @Test
    void testDatabaseConstraintViolations() {
        // Test 1: Lead without contact (should fail)
        assertThatThrownBy(() -> {
            Lead invalidLead = Lead.builder()
                    .contact(null)
                    .owner(testUser)
                    .status(Lead.LeadStatus.NEW)
                    .build();
            leadRepository.save(invalidLead);
        }).isInstanceOf(Exception.class);

        // Test 2: Lead without owner (should fail)
        Contact contact = createTestContact("Constraint Test Contact", "919876543214");
        assertThatThrownBy(() -> {
            Lead invalidLead = Lead.builder()
                    .contact(contact)
                    .owner(null)
                    .status(Lead.LeadStatus.NEW)
                    .build();
            leadRepository.save(invalidLead);
        }).isInstanceOf(Exception.class);

        // Test 3: Lead with non-existent contact ID (should fail)
        assertThatThrownBy(() -> {
            Contact nonExistentContact = Contact.builder()
                    .id(UUID.randomUUID())
                    .waId("nonexistent")
                    .name("Non-existent")
                    .owner(testUser)
                    .build();
            // Don't save the contact, just reference it
            Lead invalidLead = Lead.builder()
                    .contact(nonExistentContact)
                    .owner(testUser)
                    .status(Lead.LeadStatus.NEW)
                    .build();
            leadRepository.save(invalidLead);
        }).isInstanceOf(Exception.class);
    }

    @Test
    void testValidationEdgeCases() {
        Contact contact = createTestContact("Validation Test Contact", "919876543215");

        // Test validation with null contact
        assertThatThrownBy(() -> 
                leadService.validateLeadCreation(null, testUser, "NEW_ENQUIRY"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Contact cannot be null");

        // Test validation with null owner
        assertThatThrownBy(() -> 
                leadService.validateLeadCreation(contact, null, "NEW_ENQUIRY"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Owner cannot be null");

        // Test validation with null enquiry type
        assertThatThrownBy(() -> 
                leadService.validateLeadCreation(contact, testUser, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Enquiry type cannot be null");

        // Test validation with invalid enquiry type
        assertThatThrownBy(() -> 
                leadService.validateLeadCreation(contact, testUser, "INVALID_TYPE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid enquiry type");

        // Test validation with contact belonging to different owner
        User otherUser = User.builder()
                .email("other@example.com")
                .password("password")
                .businessName("Other Business")
                .businessSubType("SALON")
                .build();
        otherUser = userRepository.save(otherUser);

        Contact otherContact = Contact.builder()
                .waId("919876543216")
                .name("Other Contact")
                .owner(otherUser)
                .source("Test")
                .build();
        Contact savedOtherContact = contactRepository.save(otherContact);

        assertThatThrownBy(() -> 
                leadService.validateLeadCreation(savedOtherContact, testUser, "NEW_ENQUIRY"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Contact does not belong to the specified owner");
    }

    @Test
    void testTooManyActiveLeadsValidation() {
        // Given: A contact with maximum allowed active leads (10)
        Contact contact = createTestContact("Max Leads Contact", "919876543217");
        
        // Create 10 active leads (at the limit)
        for (int i = 0; i < 10; i++) {
            createTestLead(contact, Lead.LeadStatus.NEW);
        }

        // When: Trying to validate creation of 11th lead
        // Then: Should throw exception
        assertThatThrownBy(() -> 
                leadService.validateLeadCreation(contact, testUser, "NEW_ENQUIRY"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Contact has too many active leads")
                .hasMessageContaining("Maximum allowed: 10");
    }

    @Test
    void testRevenueCalculationWithNullValues() {
        // Given: Leads with various null deal values
        Contact contact1 = createTestContact("Revenue Test 1", "919876543218");
        Contact contact2 = createTestContact("Revenue Test 2", "919876543219");

        // Lead with null deal value
        createTestLead(contact1, Lead.LeadStatus.CLOSED_WON);

        // Lead with zero deal value
        Lead leadWithZero = createTestLead(contact1, Lead.LeadStatus.CLOSED_WON);
        leadWithZero.setDealValue(BigDecimal.ZERO);
        leadWithZero.setPaymentStatus(Lead.PaymentStatus.PAID);
        leadRepository.save(leadWithZero);

        // Lead with valid deal value
        Lead leadWithValue = createTestLead(contact2, Lead.LeadStatus.CLOSED_WON);
        leadWithValue.setDealValue(BigDecimal.valueOf(1000));
        leadWithValue.setPaymentStatus(Lead.PaymentStatus.PAID);
        leadRepository.save(leadWithValue);

        // When: Getting revenue report
        var report = leadService.getRevenueReport(testUser);

        // Then: Only leads with non-null deal values are included
        assertThat(report.getTotalPipelineValue()).isEqualByComparingTo(BigDecimal.valueOf(1000));
        assertThat(report.getReceivedRevenue()).isEqualByComparingTo(BigDecimal.valueOf(1000));
        assertThat(report.getTotalDeals()).isEqualTo(2); // Zero value is counted
        assertThat(report.getPaidDeals()).isEqualTo(2);
    }

    @Test
    void testEnquiryOperationsWithEmptyLead() {
        // Given: A lead with no enquiries
        Contact contact = createTestContact("Empty Enquiry Contact", "919876543220");
        Lead lead = createTestLead(contact, Lead.LeadStatus.NEW);

        // When: Getting enquiries
        var enquiries = leadService.getEnquiries(lead.getId(), testUser);

        // Then: Empty list is returned
        assertThat(enquiries).isEmpty();

        // When: Trying to delete non-existent enquiry
        // Then: Should throw exception
        assertThatThrownBy(() -> 
                leadService.deleteEnquiry(lead.getId(), "non-existent-id", testUser))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Enquiry not found");

        // When: Trying to update non-existent enquiry
        // Then: Should throw exception
        var updateRequest = new com.chatcrmlite.backend.dto.EnquiryRequest();
        updateRequest.setMessage("Updated message");
        
        assertThatThrownBy(() -> 
                leadService.updateEnquiry(lead.getId(), "non-existent-id", updateRequest, testUser))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Enquiry not found");
    }

    // ── Test Utilities ─────────────────────────────────────────────────────

    private Contact createTestContact(String name, String waId) {
        Contact contact = Contact.builder()
                .waId(waId)
                .name(name)
                .owner(testUser)
                .source("Test")
                .build();
        return contactRepository.save(contact);
    }

    private Lead createTestLead(Contact contact, Lead.LeadStatus status) {
        Lead lead = Lead.builder()
                .contact(contact)
                .owner(testUser)
                .status(status)
                .build();
        return leadRepository.save(lead);
    }
}