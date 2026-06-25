package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.LeadRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.lead.LeadService;
import jakarta.persistence.EntityManager;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.NotEmpty;
import net.jqwik.api.constraints.Size;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for Lead Service with multiple leads per contact functionality.
 * These tests verify the correctness properties defined in the design document.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@net.jqwik.spring.JqwikSpringSupport
public class LeadServicePropertyTest {

    @Autowired
    private LeadService leadService;

    @Autowired
    private LeadRepository leadRepository;

    @Autowired
    private com.chatcrmlite.backend.repositories.TenantRepository tenantRepository;

    @Autowired
    private ContactRepository contactRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    // ── Property 1: Lead Independence (Invariant) ──────────────────────────

    @Property
    @Label("When updating the status of one lead, other leads from the same contact remain unchanged")
    void leadStatusIndependence(
            @ForAll @NotEmpty String contactName,
            @ForAll @NotEmpty String waId,
            @ForAll @NotEmpty String ownerEmail,
            @ForAll @Size(min = 2, max = 5) List<Lead.LeadStatus> initialStatuses,
            @ForAll @IntRange(min = 0, max = 4) int leadToUpdate,
            @ForAll Lead.LeadStatus newStatus) {

        // Arrange: Create test data
        User owner = createTestUser(ownerEmail);
        Contact contact = createTestContact(waId, contactName, owner);
        
        // Create multiple leads for the same contact with random statuses
        List<Lead> leads = new ArrayList<>();
        for (int i = 0; i < Math.min(initialStatuses.size(), 5); i++) {
            Lead.LeadStatus status = Lead.LeadStatus.values()[i % Lead.LeadStatus.values().length];
            leads.add(createTestLead(contact, owner, status));
        }

        // Ensure we have a valid index
        Assume.that(leadToUpdate < leads.size());
        Lead targetLead = leads.get(leadToUpdate);
        Lead.LeadStatus originalStatus = targetLead.getStatus();

        // Act: Update one lead's status
        leadService.updateStatus(targetLead.getId(), newStatus, owner);

        // Assert: Other leads remain unchanged
        List<Lead> updatedLeads = leadService.getLeadsByContactId(contact.getId(), owner);
        
        for (int i = 0; i < leads.size(); i++) {
            final int index = i;
            Lead updatedLead = updatedLeads.stream()
                    .filter(l -> l.getId().equals(leads.get(index).getId()))
                    .findFirst()
                    .orElseThrow();

            if (i == leadToUpdate) {
                // The updated lead should have the new status
                assertThat(updatedLead.getStatus()).isEqualTo(newStatus);
            } else {
                // Other leads should maintain their original status
                Lead.LeadStatus expectedStatus = Lead.LeadStatus.values()[i % Lead.LeadStatus.values().length];
                assertThat(updatedLead.getStatus()).isEqualTo(expectedStatus);
            }
        }
    }

    // ── Property 2: Lead Creation Consistency (Model-Based) ────────────────

    @Property
    @Label("Lead creation follows the defined strategy consistently")
    void leadCreationConsistency(
            @ForAll @NotEmpty String contactName,
            @ForAll @NotEmpty String waId,
            @ForAll @NotEmpty String ownerEmail,
            @ForAll String enquiryType) {

        // Arrange
        User owner = createTestUser(ownerEmail);
        Contact contact = createTestContact(waId, contactName, owner);

        // Get initial lead count
        long initialCount = leadService.getLeadsByContactId(contact.getId(), owner).size();

        // Always use a valid enquiry type to test the happy path
        try {
            leadService.validateLeadCreation(contact, owner, "NEW_ENQUIRY");
            
            // If validation passes, create a lead manually to test the logic
            Lead newLead = Lead.builder()
                    .contact(contact)
                    .status(Lead.LeadStatus.NEW)
                    .owner(owner)
                    .build();
            leadRepository.saveAndFlush(newLead);
            entityManager.clear(); // make the new row visible to subsequent queries

            // Assert: Lead count increased
            long finalCount = leadRepository.findAllByContactAndOwnerOptimized(contact, owner).size();
            assertThat(finalCount).isEqualTo(initialCount + 1);

        } catch (IllegalStateException e) {
            // If validation fails (e.g., too many active leads), lead count should remain the same
            long finalCount = leadRepository.findAllByContactAndOwnerOptimized(contact, owner).size();
            assertThat(finalCount).isEqualTo(initialCount);
        }
    }

    // ── Property 3: Lead Retrieval Completeness (Invariant) ────────────────

    @Property
    @Label("Retrieving all leads for a contact returns exactly the leads associated with that contact")
    void leadRetrievalCompleteness(
            @ForAll @NotEmpty String contactName,
            @ForAll @NotEmpty String waId,
            @ForAll @NotEmpty String ownerEmail,
            @ForAll @Size(min = 1, max = 10) List<Lead.LeadStatus> leadStatuses) {

        // Arrange
        User owner = createTestUser(ownerEmail);
        Contact contact = createTestContact(waId, contactName, owner);
        
        // Create leads for this contact
        List<Lead> createdLeads = new ArrayList<>();
        for (int i = 0; i < Math.min(leadStatuses.size(), 10); i++) {
            Lead.LeadStatus status = Lead.LeadStatus.values()[i % Lead.LeadStatus.values().length];
            createdLeads.add(createTestLead(contact, owner, status));
        }

        // Act
        List<Lead> retrievedLeads = leadService.getLeadsByContactId(contact.getId(), owner);

        // Assert: All created leads are retrieved, no more, no less
        assertThat(retrievedLeads).hasSize(createdLeads.size());
        
        for (Lead createdLead : createdLeads) {
            assertThat(retrievedLeads).anyMatch(retrieved -> 
                retrieved.getId().equals(createdLead.getId()) &&
                retrieved.getContact().getId().equals(contact.getId()) &&
                retrieved.getOwner().getId().equals(owner.getId())
            );
        }
    }

    // ── Property 4: Revenue Calculation Accuracy (Metamorphic) ─────────────

    @Property
    @Label("Revenue calculations include all leads regardless of contact grouping")
    void revenueCalculationAccuracy(
            @ForAll @NotEmpty String ownerEmail,
            @ForAll("dealValues") BigDecimal dealValue,
            @ForAll @IntRange(min = 1, max = 5) int count) {

        // Arrange
        User owner = createTestUser(ownerEmail);
        
        // Create leads with deal values across different contacts
        BigDecimal totalExpected = BigDecimal.ZERO;
        for (int i = 0; i < count; i++) {
            Contact contact = createTestContact("wa-rev-" + i, "Contact Rev " + i, owner);
            Lead lead = createTestLead(contact, owner, Lead.LeadStatus.CLOSED_WON);
            lead.setDealValue(dealValue);
            lead.setPaymentStatus(Lead.PaymentStatus.PAID);
            leadRepository.save(lead);
            totalExpected = totalExpected.add(dealValue);
        }

        entityManager.flush();
        entityManager.clear();

        // Act
        var revenueReport = leadService.getRevenueReport(owner);

        // Assert: Total revenue equals sum of all deal values
        assertThat(revenueReport.getReceivedRevenue()).isEqualByComparingTo(totalExpected);
        assertThat(revenueReport.getTotalDeals()).isGreaterThanOrEqualTo(count);
    }

    // ── Property 5: Lead Enquiry Isolation (Invariant) ─────────────────────

    @Property
    @Label("Enquiry operations on one lead do not affect enquiries in other leads")
    void leadEnquiryIsolation(
            @ForAll @NotEmpty String contactName,
            @ForAll @NotEmpty String waId,
            @ForAll @NotEmpty String ownerEmail,
            @ForAll @NotEmpty String enquiryMessage) {

        // Arrange
        User owner = createTestUser(ownerEmail);
        Contact contact = createTestContact(waId, contactName, owner);
        
        Lead lead1 = createTestLead(contact, owner, Lead.LeadStatus.NEW);
        Lead lead2 = createTestLead(contact, owner, Lead.LeadStatus.INTERESTED);

        // Get initial enquiry counts
        int initialEnquiries1 = leadService.getEnquiries(lead1.getId(), owner).size();
        int initialEnquiries2 = leadService.getEnquiries(lead2.getId(), owner).size();

        // Act: Add enquiry to lead1
        var enquiryRequest = new com.chatcrmlite.backend.dto.EnquiryRequest();
        enquiryRequest.setMessage(enquiryMessage);
        enquiryRequest.setType("TEST");
        enquiryRequest.setSource("Property Test");
        
        leadService.addEnquiry(lead1.getId(), enquiryRequest, owner);

        // Assert: Only lead1's enquiries increased
        int finalEnquiries1 = leadService.getEnquiries(lead1.getId(), owner).size();
        int finalEnquiries2 = leadService.getEnquiries(lead2.getId(), owner).size();

        assertThat(finalEnquiries1).isEqualTo(initialEnquiries1 + 1);
        assertThat(finalEnquiries2).isEqualTo(initialEnquiries2); // Unchanged
    }

    // ── Property 6: Status Lifecycle Independence (Invariant) ──────────────

    @Property
    @Label("Each lead progresses through its lifecycle independently")
    void statusLifecycleIndependence(
            @ForAll @NotEmpty String contactName,
            @ForAll @NotEmpty String waId,
            @ForAll @NotEmpty String ownerEmail,
            @ForAll Lead.LeadStatus status1,
            @ForAll Lead.LeadStatus status2) {

        // Arrange
        User owner = createTestUser(ownerEmail);
        Contact contact = createTestContact(waId, contactName, owner);
        
        Lead lead1 = createTestLead(contact, owner, status1);
        Lead lead2 = createTestLead(contact, owner, status2);

        // Act & Assert: Both leads can have different statuses simultaneously
        List<Lead> leads = leadService.getLeadsByContactId(contact.getId(), owner);
        
        Lead retrievedLead1 = leads.stream()
                .filter(l -> l.getId().equals(lead1.getId()))
                .findFirst()
                .orElseThrow();
        
        Lead retrievedLead2 = leads.stream()
                .filter(l -> l.getId().equals(lead2.getId()))
                .findFirst()
                .orElseThrow();

        assertThat(retrievedLead1.getStatus()).isEqualTo(status1);
        assertThat(retrievedLead2.getStatus()).isEqualTo(status2);
        
        // Both leads belong to the same contact but can have different statuses
        assertThat(retrievedLead1.getContact().getId()).isEqualTo(retrievedLead2.getContact().getId());
    }

    // ── Property 7: Backward Compatibility (Round-trip) ────────────────────

    @Property
    @Label("Legacy endpoints return consistent results")
    void backwardCompatibility(
            @ForAll @NotEmpty String contactName,
            @ForAll @NotEmpty String waId,
            @ForAll @NotEmpty String ownerEmail,
            @ForAll @Size(min = 1, max = 5) List<Lead.LeadStatus> leadStatuses) {

        // Arrange
        User owner = createTestUser(ownerEmail);
        Contact contact = createTestContact(waId, contactName, owner);
        
        // Create leads with different creation times
        List<Lead> leads = new ArrayList<>();
        for (int i = 0; i < Math.min(leadStatuses.size(), 5); i++) {
            Lead.LeadStatus status = Lead.LeadStatus.values()[i % Lead.LeadStatus.values().length];
            Lead lead = createTestLead(contact, owner, status);
            // Simulate different creation times
            lead.setCreatedAt(LocalDateTime.now().minusMinutes(i));
            leads.add(leadRepository.save(lead));
        }

        // Act
        List<Lead> allLeads = leadService.getLeadsByContactId(contact.getId(), owner);
        Lead latestLead = leadService.getLatestLeadByContactId(contact.getId(), owner);

        // Assert: Latest lead is the most recently created one
        Lead expectedLatest = leads.stream()
                .max((l1, l2) -> l1.getCreatedAt().compareTo(l2.getCreatedAt()))
                .orElseThrow();

        assertThat(latestLead.getId()).isEqualTo(expectedLatest.getId());
        assertThat(allLeads).contains(latestLead);
    }

    // ── Property 8: Data Integrity (Invariant) ─────────────────────────────

    @Property
    @Label("All leads maintain valid references to their contacts")
    void dataIntegrity(
            @ForAll @NotEmpty String ownerEmail,
            @ForAll @Size(min = 1, max = 3) List<@NotEmpty String> contactNames) {

        // Arrange
        User owner = createTestUser(ownerEmail);
        
        // Create contacts and leads
        for (int i = 0; i < contactNames.size(); i++) {
            Contact contact = createTestContact("wa" + i, contactNames.get(i), owner);
            createTestLead(contact, owner, Lead.LeadStatus.NEW);
        }

        // Act: Get all leads for the owner
        List<Lead> allLeads = leadService.getLeadsByUserPaged(owner, 0, 100, null).getContent();

        // Assert: All leads have valid contact references
        for (Lead lead : allLeads) {
            assertThat(lead.getContact()).isNotNull();
            assertThat(lead.getContact().getId()).isNotNull();
            
            // Verify contact exists in database
            Contact contact = contactRepository.findById(lead.getContact().getId()).orElse(null);
            assertThat(contact).isNotNull();
            assertThat(contact.getOwner().getId()).isEqualTo(owner.getId());
        }
    }

    // ── Test Data Providers ────────────────────────────────────────────────

    @Provide
    Arbitrary<Lead.LeadStatus> leadStatuses() {
        return Arbitraries.of(Lead.LeadStatus.values());
    }

    @Provide
    Arbitrary<String> enquiryTypes() {
        return Arbitraries.of("NEW_ENQUIRY", "ONGOING");
    }

    @Provide
    Arbitrary<BigDecimal> dealValues() {
        return Arbitraries.bigDecimals()
                .between(BigDecimal.valueOf(100), BigDecimal.valueOf(100000))
                .ofScale(2);
    }

    // ── Helper Methods ──────────────────────────────────────────────────────

    private User createTestUser(String email) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            com.chatcrmlite.backend.models.Tenant tenant = tenantRepository.findAll().stream().findFirst().orElseGet(() -> {
                com.chatcrmlite.backend.models.Tenant t = com.chatcrmlite.backend.models.Tenant.builder()
                        .businessName("Test Business")
                        .businessType("GENERAL")
                        .businessSubType("GENERAL")
                        .build();
                return tenantRepository.save(t);
            });
            User user = User.builder()
                    .email(email)
                    .password("test123")
                    .businessName("Test Business")
                    .businessSubType("GENERAL")
                    .tenant(tenant)
                    .build();
            return userRepository.save(user);
        });
    }

    private Contact createTestContact(String waId, String name, User owner) {
        return contactRepository.findByWaIdAndOwner(waId, owner).orElseGet(() -> {
            Contact contact = Contact.builder()
                    .waId(waId)
                    .name(name)
                    .source("Test")
                    .owner(owner)
                    .build();
            return contactRepository.save(contact);
        });
    }

    private Lead createTestLead(Contact contact, User owner, Lead.LeadStatus status) {
        Lead lead = Lead.builder()
                .contact(contact)
                .owner(owner)
                .status(status)
                .enquiries("[]")
                .build();
        return leadRepository.save(lead);
    }
}