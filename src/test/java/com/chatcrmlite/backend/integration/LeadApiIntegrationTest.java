package com.chatcrmlite.backend.integration;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.LeadRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureWebMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for Lead API endpoints with multiple leads per contact functionality.
 */
@SpringBootTest
@AutoConfigureWebMvc
@ActiveProfiles("test")
@Transactional
public class LeadApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ContactRepository contactRepository;

    @Autowired
    private LeadRepository leadRepository;

    private User testUser;
    private Contact testContact;

    @BeforeEach
    void setUp() {
        // Create test user
        testUser = User.builder()
                .email("test@example.com")
                .password("test123")
                .businessName("Test Business")
                .businessSubType("GENERAL")
                .build();
        testUser = userRepository.save(testUser);

        // Create test contact
        testContact = Contact.builder()
                .waId("1234567890")
                .name("Test Contact")
                .source("WhatsApp")
                .owner(testUser)
                .build();
        testContact = contactRepository.save(testContact);
    }

    @Test
    @WithMockUser(username = "test@example.com")
    void testGetAllLeadsForContact() throws Exception {
        // Arrange: Create multiple leads for the same contact
        Lead lead1 = createTestLead(testContact, Lead.LeadStatus.NEW);
        Lead lead2 = createTestLead(testContact, Lead.LeadStatus.INTERESTED);
        Lead lead3 = createTestLead(testContact, Lead.LeadStatus.FOLLOW_UP);

        // Act & Assert: Get all leads for contact
        mockMvc.perform(get("/api/v1/leads/contact/{contactId}", testContact.getId()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[*].id", containsInAnyOrder(
                        lead1.getId().toString(),
                        lead2.getId().toString(),
                        lead3.getId().toString()
                )))
                .andExpect(jsonPath("$[*].status", containsInAnyOrder("NEW", "INTERESTED", "FOLLOW_UP")))
                .andExpect(jsonPath("$[*].contact.id", everyItem(equalTo(testContact.getId().toString()))));
    }

    @Test
    @WithMockUser(username = "test@example.com")
    void testGetLatestLeadForContact() throws Exception {
        // Arrange: Create multiple leads with different creation times
        Lead oldLead = createTestLead(testContact, Lead.LeadStatus.NEW);
        Thread.sleep(10); // Ensure different timestamps
        Lead latestLead = createTestLead(testContact, Lead.LeadStatus.INTERESTED);

        // Act & Assert: Get latest lead for contact
        mockMvc.perform(get("/api/v1/leads/contact/{contactId}/latest", testContact.getId()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id", equalTo(latestLead.getId().toString())))
                .andExpect(jsonPath("$.status", equalTo("INTERESTED")))
                .andExpect(jsonPath("$.contact.id", equalTo(testContact.getId().toString())));
    }

    @Test
    @WithMockUser(username = "test@example.com")
    void testUpdateLeadStatusIndependence() throws Exception {
        // Arrange: Create multiple leads
        Lead lead1 = createTestLead(testContact, Lead.LeadStatus.NEW);
        Lead lead2 = createTestLead(testContact, Lead.LeadStatus.INTERESTED);

        // Act: Update one lead's status
        mockMvc.perform(put("/api/v1/leads/{leadId}/status", lead1.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"FOLLOW_UP\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", equalTo(lead1.getId().toString())))
                .andExpect(jsonPath("$.status", equalTo("FOLLOW_UP")));

        // Assert: Other lead remains unchanged
        mockMvc.perform(get("/api/v1/leads/{leadId}", lead2.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", equalTo(lead2.getId().toString())))
                .andExpect(jsonPath("$.status", equalTo("INTERESTED")));
    }

    @Test
    @WithMockUser(username = "test@example.com")
    void testEnquiryIsolationBetweenLeads() throws Exception {
        // Arrange: Create multiple leads
        Lead lead1 = createTestLead(testContact, Lead.LeadStatus.NEW);
        Lead lead2 = createTestLead(testContact, Lead.LeadStatus.INTERESTED);

        // Act: Add enquiry to lead1
        String enquiryRequest = """
            {
                "message": "I need pricing information",
                "type": "PRICING",
                "source": "API Test"
            }
            """;

        mockMvc.perform(post("/api/v1/leads/{leadId}/enquiries", lead1.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(enquiryRequest))
                .andExpect(status().isOk());

        // Assert: Lead1 has the enquiry
        mockMvc.perform(get("/api/v1/leads/{leadId}/enquiries", lead1.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].message", equalTo("I need pricing information")));

        // Assert: Lead2 has no enquiries
        mockMvc.perform(get("/api/v1/leads/{leadId}/enquiries", lead2.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @WithMockUser(username = "test@example.com")
    void testRevenueCalculationWithMultipleLeads() throws Exception {
        // Arrange: Create leads with different deal values
        Lead lead1 = createTestLead(testContact, Lead.LeadStatus.CLOSED_WON);
        lead1.setDealValue(new BigDecimal("1000.00"));
        lead1.setPaymentStatus(Lead.PaymentStatus.PAID);
        leadRepository.save(lead1);

        Lead lead2 = createTestLead(testContact, Lead.LeadStatus.CLOSED_WON);
        lead2.setDealValue(new BigDecimal("2500.00"));
        lead2.setPaymentStatus(Lead.PaymentStatus.PAID);
        leadRepository.save(lead2);

        Lead lead3 = createTestLead(testContact, Lead.LeadStatus.INTERESTED);
        lead3.setDealValue(new BigDecimal("500.00"));
        lead3.setPaymentStatus(Lead.PaymentStatus.PENDING);
        leadRepository.save(lead3);

        // Act & Assert: Get revenue report
        mockMvc.perform(get("/api/v1/leads/revenue-report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receivedRevenue", equalTo(3500.00)))
                .andExpect(jsonPath("$.pendingRevenue", equalTo(500.00)))
                .andExpect(jsonPath("$.totalPipelineValue", equalTo(4000.00)))
                .andExpect(jsonPath("$.totalDeals", equalTo(3)))
                .andExpect(jsonPath("$.paidDeals", equalTo(2)))
                .andExpect(jsonPath("$.pendingDeals", equalTo(1)));
    }

    @Test
    @WithMockUser(username = "test@example.com")
    void testLeadDealUpdateIndependence() throws Exception {
        // Arrange: Create multiple leads
        Lead lead1 = createTestLead(testContact, Lead.LeadStatus.INTERESTED);
        Lead lead2 = createTestLead(testContact, Lead.LeadStatus.FOLLOW_UP);

        // Act: Update deal info for lead1
        String dealUpdate = """
            {
                "dealValue": 1500.00,
                "dealLabel": "Premium Service",
                "currency": "USD",
                "paymentStatus": "PENDING"
            }
            """;

        mockMvc.perform(put("/api/v1/leads/{leadId}/deal", lead1.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dealUpdate))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dealValue", equalTo(1500.00)))
                .andExpect(jsonPath("$.dealLabel", equalTo("Premium Service")))
                .andExpect(jsonPath("$.currency", equalTo("USD")))
                .andExpect(jsonPath("$.paymentStatus", equalTo("PENDING")));

        // Assert: Lead2 remains unchanged
        mockMvc.perform(get("/api/v1/leads/{leadId}", lead2.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dealValue").doesNotExist())
                .andExpect(jsonPath("$.dealLabel").doesNotExist())
                .andExpect(jsonPath("$.paymentStatus", equalTo("NONE")));
    }

    @Test
    @WithMockUser(username = "test@example.com")
    void testContactWithNoLeads() throws Exception {
        // Arrange: Create contact with no leads
        Contact emptyContact = Contact.builder()
                .waId("9876543210")
                .name("Empty Contact")
                .source("WhatsApp")
                .owner(testUser)
                .build();
        emptyContact = contactRepository.save(emptyContact);

        // Act & Assert: Get leads for contact with no leads
        mockMvc.perform(get("/api/v1/leads/contact/{contactId}", emptyContact.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        // Act & Assert: Get latest lead for contact with no leads
        mockMvc.perform(get("/api/v1/leads/contact/{contactId}/latest", emptyContact.getId()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "test@example.com")
    void testLeadPipelineWithMultipleLeadsPerContact() throws Exception {
        // Arrange: Create leads in different pipeline stages
        Lead newLead = createTestLead(testContact, Lead.LeadStatus.NEW);
        Lead interestedLead = createTestLead(testContact, Lead.LeadStatus.INTERESTED);
        Lead followUpLead = createTestLead(testContact, Lead.LeadStatus.FOLLOW_UP);
        Lead bookedLead = createTestLead(testContact, Lead.LeadStatus.BOOKED);
        Lead closedWonLead = createTestLead(testContact, Lead.LeadStatus.CLOSED_WON);

        // Act & Assert: Get leads by status
        mockMvc.perform(get("/api/v1/leads/status/NEW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id", equalTo(newLead.getId().toString())));

        mockMvc.perform(get("/api/v1/leads/status/INTERESTED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id", equalTo(interestedLead.getId().toString())));

        mockMvc.perform(get("/api/v1/leads/status/CLOSED_WON"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id", equalTo(closedWonLead.getId().toString())));
    }

    @Test
    @WithMockUser(username = "test@example.com")
    void testBackwardCompatibilityWithSingleLead() throws Exception {
        // Arrange: Create single lead (legacy scenario)
        Lead singleLead = createTestLead(testContact, Lead.LeadStatus.INTERESTED);

        // Act & Assert: Legacy endpoints still work
        mockMvc.perform(get("/api/v1/leads/contact/{contactId}/latest", testContact.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", equalTo(singleLead.getId().toString())));

        mockMvc.perform(get("/api/v1/leads/contact/{contactId}", testContact.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id", equalTo(singleLead.getId().toString())));
    }

    @Test
    @WithMockUser(username = "test@example.com")
    void testConcurrentLeadOperations() throws Exception {
        // Arrange: Create multiple leads
        Lead lead1 = createTestLead(testContact, Lead.LeadStatus.NEW);
        Lead lead2 = createTestLead(testContact, Lead.LeadStatus.INTERESTED);

        // Act: Perform concurrent operations (simulated)
        // Update lead1 status
        mockMvc.perform(put("/api/v1/leads/{leadId}/status", lead1.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"FOLLOW_UP\"}"))
                .andExpect(status().isOk());

        // Add enquiry to lead2
        String enquiryRequest = """
            {
                "message": "Follow up question",
                "type": "FOLLOW_UP",
                "source": "API Test"
            }
            """;

        mockMvc.perform(post("/api/v1/leads/{leadId}/enquiries", lead2.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(enquiryRequest))
                .andExpect(status().isOk());

        // Assert: Both operations succeeded independently
        mockMvc.perform(get("/api/v1/leads/{leadId}", lead1.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("FOLLOW_UP")));

        mockMvc.perform(get("/api/v1/leads/{leadId}/enquiries", lead2.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].message", equalTo("Follow up question")));
    }

    // ── Helper Methods ──────────────────────────────────────────────────────

    private Lead createTestLead(Contact contact, Lead.LeadStatus status) {
        Lead lead = Lead.builder()
                .contact(contact)
                .owner(testUser)
                .status(status)
                .enquiries("[]")
                .build();
        return leadRepository.save(lead);
    }
}