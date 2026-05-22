package com.chatcrmlite.backend.integration;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.UserSession;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.LeadRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.repositories.TenantRepository;
import com.chatcrmlite.backend.repositories.UserSessionRepository;
import com.chatcrmlite.backend.security.JwtUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for Lead Controller API endpoints with multiple leads per contact.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
public class LeadControllerMultipleLeadsIntegrationTest {

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

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserSessionRepository sessionRepository;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private User testUser;
    private String authToken;
    private Contact testContact;

    @BeforeEach
    void setUp() {
        // Create test tenant
        Tenant tenant = Tenant.builder()
                .businessName("Test Company")
                .build();
        tenant = tenantRepository.save(tenant);

        // Create test user with tenant
        testUser = User.builder()
                .email("test@example.com")
                .password(passwordEncoder.encode("password"))
                .businessName("Test Business")
                .businessSubType("RESTAURANT")
                .tenant(tenant)
                .build();
        testUser = userRepository.save(testUser);

        // Create active user session for JWT validation in AuthTokenFilter
        UserSession session = UserSession.builder()
                .tokenId("test-session")
                .user(testUser)
                .status("ACTIVE")
                .createdAt(java.time.LocalDateTime.now())
                .expiresAt(java.time.LocalDateTime.now().plusDays(1))
                .build();
        sessionRepository.save(session);

        // Generate auth token with matching tokenId
        authToken = jwtUtils.generateJwtToken(testUser.getEmail(), "test-session");

        // Create test contact
        testContact = Contact.builder()
                .waId("919876543210")
                .name("Test Contact")
                .owner(testUser)
                .source("Test")
                .build();
        testContact = contactRepository.save(testContact);
    }

    @Test
    void testGetAllLeadsForContact() throws Exception {
        // Given: A contact with multiple leads
        Lead lead1 = createTestLead(testContact, Lead.LeadStatus.NEW);
        Lead lead2 = createTestLead(testContact, Lead.LeadStatus.INTERESTED);
        Lead lead3 = createTestLead(testContact, Lead.LeadStatus.CLOSED_WON);

        // When: Getting all leads for the contact
        mockMvc.perform(get("/api/v1/leads/contact/{contactId}", testContact.getId())
                        .header("Authorization", "Bearer " + authToken))
                // Then: All leads are returned
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[*].id", containsInAnyOrder(
                        lead1.getId().toString(),
                        lead2.getId().toString(),
                        lead3.getId().toString()
                )))
                .andExpect(jsonPath("$[*].status", containsInAnyOrder("NEW", "INTERESTED", "CLOSED_WON")));
    }

    @Test
    void testGetLatestLeadForContact() throws Exception {
        // Given: A contact with multiple leads created at different times
        Lead oldLead = createTestLead(testContact, Lead.LeadStatus.CLOSED_WON);
        Thread.sleep(10); // Ensure different timestamps
        Lead latestLead = createTestLead(testContact, Lead.LeadStatus.NEW);

        // When: Getting the latest lead for the contact
        mockMvc.perform(get("/api/v1/leads/contact/{contactId}/latest", testContact.getId())
                        .header("Authorization", "Bearer " + authToken))
                // Then: The most recent lead is returned
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(latestLead.getId().toString())))
                .andExpect(jsonPath("$.status", is("NEW")));
    }

    @Test
    void testGetLeadsForContactWithNoLeads() throws Exception {
        // Given: A contact with no leads
        Contact emptyContact = Contact.builder()
                .waId("919876543211")
                .name("Empty Contact")
                .owner(testUser)
                .source("Test")
                .build();
        emptyContact = contactRepository.save(emptyContact);

        // When: Getting leads for the contact
        mockMvc.perform(get("/api/v1/leads/contact/{contactId}", emptyContact.getId())
                        .header("Authorization", "Bearer " + authToken))
                // Then: Empty list is returned
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void testGetLatestLeadForContactWithNoLeads() throws Exception {
        // Given: A contact with no leads
        Contact emptyContact = Contact.builder()
                .waId("919876543212")
                .name("Empty Contact 2")
                .owner(testUser)
                .source("Test")
                .build();
        emptyContact = contactRepository.save(emptyContact);

        // When: Getting latest lead for the contact
        mockMvc.perform(get("/api/v1/leads/contact/{contactId}/latest", emptyContact.getId())
                        .header("Authorization", "Bearer " + authToken))
                // Then: 500 is returned because LeadService throws RuntimeException
                .andExpect(status().isInternalServerError());
    }

    @Test
    void testUpdateLeadStatusIndependence() throws Exception {
        // Given: A contact with multiple leads
        Lead lead1 = createTestLead(testContact, Lead.LeadStatus.NEW);
        Lead lead2 = createTestLead(testContact, Lead.LeadStatus.INTERESTED);
        Lead lead3 = createTestLead(testContact, Lead.LeadStatus.FOLLOW_UP);

        // When: Updating status of one lead
        mockMvc.perform(patch("/api/v1/leads/{leadId}/status", lead2.getId())
                        .header("Authorization", "Bearer " + authToken)
                        .param("status", "CLOSED_WON"))
                // Then: Only that lead's status is updated
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(lead2.getId().toString())))
                .andExpect(jsonPath("$.status", is("CLOSED_WON")));

        // And: Other leads remain unchanged
        mockMvc.perform(get("/api/v1/leads/contact/{contactId}", testContact.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[?(@.id == '" + lead1.getId() + "')].status", contains("NEW")))
                .andExpect(jsonPath("$[?(@.id == '" + lead2.getId() + "')].status", contains("CLOSED_WON")))
                .andExpect(jsonPath("$[?(@.id == '" + lead3.getId() + "')].status", contains("FOLLOW_UP")));
    }

    @Test
    void testAddEnquiryToSpecificLead() throws Exception {
        // Given: A contact with multiple leads
        Lead lead1 = createTestLead(testContact, Lead.LeadStatus.NEW);
        Lead lead2 = createTestLead(testContact, Lead.LeadStatus.INTERESTED);

        // When: Adding enquiry to specific lead
        String enquiryRequest = """
            {
                "message": "What are your opening hours?",
                "type": "MANUAL",
                "source": "API Test"
            }
            """;

        mockMvc.perform(post("/api/v1/leads/{leadId}/enquiries", lead1.getId())
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(enquiryRequest))
                // Then: Enquiry is added to the specific lead
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(lead1.getId().toString())));

        // And: Only the target lead has the enquiry
        mockMvc.perform(get("/api/v1/leads/{leadId}/enquiries", lead1.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].message", is("What are your opening hours?")));

        // And: Other lead has no enquiries
        mockMvc.perform(get("/api/v1/leads/{leadId}/enquiries", lead2.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void testUpdateDealInfoIndependence() throws Exception {
        // Given: A contact with multiple leads
        Lead lead1 = createTestLead(testContact, Lead.LeadStatus.INTERESTED);
        Lead lead2 = createTestLead(testContact, Lead.LeadStatus.BOOKED);

        // When: Updating deal info for one lead
        String dealUpdate = """
            {
                "dealValue": 5000,
                "dealLabel": "Premium Package",
                "currency": "INR",
                "paymentStatus": "PAID"
            }
            """;

        mockMvc.perform(patch("/api/v1/leads/{leadId}/deal", lead1.getId())
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dealUpdate))
                // Then: Deal info is updated for the specific lead
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(lead1.getId().toString())))
                .andExpect(jsonPath("$.dealValue", is(5000)))
                .andExpect(jsonPath("$.dealLabel", is("Premium Package")))
                .andExpect(jsonPath("$.paymentStatus", is("PAID")));

        // And: Other lead remains unchanged
        mockMvc.perform(get("/api/v1/leads/contact/{contactId}", testContact.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + lead2.getId() + "')].dealValue").value(org.hamcrest.Matchers.contains((Object) null)))
                .andExpect(jsonPath("$[?(@.id == '" + lead2.getId() + "')].paymentStatus", org.hamcrest.Matchers.contains("NONE")));
    }

    @Test
    void testRevenueReportIncludesAllLeads() throws Exception {
        // Given: Multiple contacts with multiple leads having deal values
        Contact contact1 = createTestContact("Contact 1", "919876543213");
        Contact contact2 = createTestContact("Contact 2", "919876543214");

        // Contact 1 - Multiple leads
        Lead lead1 = createTestLeadWithDeal(contact1, Lead.LeadStatus.CLOSED_WON, 
                BigDecimal.valueOf(1000), Lead.PaymentStatus.PAID);
        Lead lead2 = createTestLeadWithDeal(contact1, Lead.LeadStatus.INTERESTED, 
                BigDecimal.valueOf(2000), Lead.PaymentStatus.PENDING);

        // Contact 2 - Multiple leads
        Lead lead3 = createTestLeadWithDeal(contact2, Lead.LeadStatus.CLOSED_WON, 
                BigDecimal.valueOf(1500), Lead.PaymentStatus.PAID);
        Lead lead4 = createTestLeadWithDeal(contact2, Lead.LeadStatus.BOOKED, 
                BigDecimal.valueOf(3000), Lead.PaymentStatus.PARTIAL);

        // When: Getting revenue report
        mockMvc.perform(get("/api/v1/leads/revenue")
                        .header("Authorization", "Bearer " + authToken))
                // Then: All leads are included in calculations
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPipelineValue", is(7500.0))) // 1000 + 2000 + 1500 + 3000
                .andExpect(jsonPath("$.receivedRevenue", is(2500.0)))    // 1000 + 1500 (PAID)
                .andExpect(jsonPath("$.pendingRevenue", is(5000.0)))     // 2000 + 3000 (PENDING + PARTIAL)
                .andExpect(jsonPath("$.totalDeals", is(4)))
                .andExpect(jsonPath("$.paidDeals", is(2)))
                .andExpect(jsonPath("$.pendingDeals", is(2)));
    }

    @Test
    void testLeadFilteringByOwner() throws Exception {
        // Given: Another user with leads
        User otherUser = User.builder()
                .email("other@example.com")
                .password(passwordEncoder.encode("password"))
                .businessName("Other Business")
                .businessSubType("SALON")
                .build();
        otherUser = userRepository.save(otherUser);

        Contact otherContact = Contact.builder()
                .waId("919876543215")
                .name("Other Contact")
                .owner(otherUser)
                .source("Test")
                .build();
        otherContact = contactRepository.save(otherContact);

        Lead otherLead = createTestLead(otherContact, Lead.LeadStatus.NEW);

        // And: Our test user's leads
        Lead ourLead = createTestLead(testContact, Lead.LeadStatus.INTERESTED);

        // When: Getting leads for our contact
        mockMvc.perform(get("/api/v1/leads/contact/{contactId}", testContact.getId())
                        .header("Authorization", "Bearer " + authToken))
                // Then: Only our user's leads are returned
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id", is(ourLead.getId().toString())));
    }

    @Test
    void testConcurrentLeadOperations() throws Exception {
        // Given: A contact with multiple leads
        Lead lead1 = createTestLead(testContact, Lead.LeadStatus.NEW);
        Lead lead2 = createTestLead(testContact, Lead.LeadStatus.INTERESTED);

        // When: Performing concurrent operations on different leads
        // Update status of lead1
        mockMvc.perform(patch("/api/v1/leads/{leadId}/status", lead1.getId())
                        .header("Authorization", "Bearer " + authToken)
                        .param("status", "FOLLOW_UP"))
                .andExpect(status().isOk());

        // Add enquiry to lead2
        String enquiryRequest = """
            {
                "message": "Concurrent enquiry",
                "type": "MANUAL",
                "source": "Concurrent Test"
            }
            """;

        mockMvc.perform(post("/api/v1/leads/{leadId}/enquiries", lead2.getId())
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(enquiryRequest))
                .andExpect(status().isOk());

        // Then: Both operations should succeed independently
        mockMvc.perform(get("/api/v1/leads/contact/{contactId}", testContact.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[?(@.id == '" + lead1.getId() + "')].status", contains("FOLLOW_UP")));

        mockMvc.perform(get("/api/v1/leads/{leadId}/enquiries", lead2.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].message", is("Concurrent enquiry")));
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

    private Lead createTestLeadWithDeal(Contact contact, Lead.LeadStatus status, 
                                       BigDecimal dealValue, Lead.PaymentStatus paymentStatus) {
        Lead lead = Lead.builder()
                .contact(contact)
                .owner(testUser)
                .status(status)
                .dealValue(dealValue)
                .paymentStatus(paymentStatus)
                .currency("INR")
                .build();
        return leadRepository.save(lead);
    }
}