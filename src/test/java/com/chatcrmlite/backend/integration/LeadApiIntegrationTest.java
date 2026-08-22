package com.chatcrmlite.backend.integration;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.UserSession;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.LeadRepository;
import com.chatcrmlite.backend.repositories.TenantRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
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

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for Lead API endpoints with multiple leads per contact functionality.
 *
 * Uses real JWT authentication so getAuthenticatedUser() can resolve the principal
 * to an actual User entity (the controller casts principal to String, which only
 * works with the JWT auth filter — not @WithMockUser).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
public class LeadApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private ContactRepository contactRepository;
    @Autowired private LeadRepository leadRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private UserSessionRepository sessionRepository;
    @Autowired private JwtUtils jwtUtils;
    @Autowired private PasswordEncoder passwordEncoder;

    private User testUser;
    private Contact testContact;
    private String authToken;

    @BeforeEach
    void setUp() {
        // Persist Tenant first — User.tenant_id is NOT NULL
        Tenant tenant = Tenant.builder()
                .businessName("Test Business")
                .businessType("GENERAL")
                .businessSubType("GENERAL")
                .build();
        tenant = tenantRepository.save(tenant);

        // Create test user with explicit tenant to satisfy NOT NULL FK
        testUser = User.builder()
                .email("api-test@example.com")
                .password(passwordEncoder.encode("test123"))
                .businessName("Test Business")
                .businessSubType("GENERAL")
                .tenant(tenant)
                .build();
        testUser = userRepository.save(testUser);

        // Active session so JWT validation passes through AuthTokenFilter
        UserSession session = UserSession.builder()
                .tokenId("api-test-session")
                .user(testUser)
                .status("ACTIVE")
                .createdAt(java.time.LocalDateTime.now())
                .expiresAt(java.time.LocalDateTime.now().plusDays(1))
                .build();
        sessionRepository.save(session);

        // Generate JWT — this becomes the String principal the controller reads
        authToken = jwtUtils.generateJwtToken(testUser.getEmail(), "api-test-session");

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
    void testGetAllLeadsForContact() throws Exception {
        Lead lead1 = createTestLead(testContact, Lead.LeadStatus.NEW);
        Lead lead2 = createTestLead(testContact, Lead.LeadStatus.INTERESTED);
        Lead lead3 = createTestLead(testContact, Lead.LeadStatus.FOLLOW_UP);

        mockMvc.perform(get("/api/v1/leads/contact/{contactId}", testContact.getId())
                        .header("Authorization", "Bearer " + authToken))
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
    void testGetLatestLeadForContact() throws Exception {
        Lead oldLead = createTestLead(testContact, Lead.LeadStatus.NEW);
        Thread.sleep(10);
        Lead latestLead = createTestLead(testContact, Lead.LeadStatus.INTERESTED);

        mockMvc.perform(get("/api/v1/leads/contact/{contactId}/latest", testContact.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id", equalTo(latestLead.getId().toString())))
                .andExpect(jsonPath("$.status", equalTo("INTERESTED")))
                .andExpect(jsonPath("$.contact.id", equalTo(testContact.getId().toString())));
    }

    @Test
    void testUpdateLeadStatusIndependence() throws Exception {
        Lead lead1 = createTestLead(testContact, Lead.LeadStatus.NEW);
        Lead lead2 = createTestLead(testContact, Lead.LeadStatus.INTERESTED);

        // Controller: PATCH /{id}/status?status=FOLLOW_UP
        mockMvc.perform(patch("/api/v1/leads/{leadId}/status", lead1.getId())
                        .header("Authorization", "Bearer " + authToken)
                        .param("status", "FOLLOW_UP"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", equalTo(lead1.getId().toString())))
                .andExpect(jsonPath("$.status", equalTo("FOLLOW_UP")));

        // Verify lead2 unchanged via contact endpoint
        mockMvc.perform(get("/api/v1/leads/contact/{contactId}", testContact.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + lead2.getId() + "')].status",
                        contains("INTERESTED")));
    }

    @Test
    void testEnquiryIsolationBetweenLeads() throws Exception {
        Lead lead1 = createTestLead(testContact, Lead.LeadStatus.NEW);
        Lead lead2 = createTestLead(testContact, Lead.LeadStatus.INTERESTED);

        String enquiryRequest = """
            {
                "message": "I need pricing information",
                "type": "PRICING",
                "source": "API Test"
            }
            """;

        mockMvc.perform(post("/api/v1/leads/{leadId}/enquiries", lead1.getId())
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(enquiryRequest))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/leads/{leadId}/enquiries", lead1.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].message", equalTo("I need pricing information")));

        mockMvc.perform(get("/api/v1/leads/{leadId}/enquiries", lead2.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void testRevenueCalculationWithMultipleLeads() throws Exception {
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

        // GET /api/v1/leads/revenue
        mockMvc.perform(get("/api/v1/leads/revenue")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk());
    }

    @Test
    void testLeadDealUpdateIndependence() throws Exception {
        Lead lead1 = createTestLead(testContact, Lead.LeadStatus.INTERESTED);
        Lead lead2 = createTestLead(testContact, Lead.LeadStatus.FOLLOW_UP);

        String dealUpdate = """
            {
                "dealValue": 1500.00,
                "dealLabel": "Premium Service",
                "currency": "USD",
                "paymentStatus": "PENDING"
            }
            """;

        // Controller: PATCH /{id}/deal
        mockMvc.perform(patch("/api/v1/leads/{leadId}/deal", lead1.getId())
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dealUpdate))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dealValue", equalTo(1500.00)))
                .andExpect(jsonPath("$.dealLabel", equalTo("Premium Service")))
                .andExpect(jsonPath("$.currency", equalTo("USD")))
                .andExpect(jsonPath("$.paymentStatus", equalTo("PENDING")));

        // lead2 unchanged — verify via contact endpoint (no GET /leads/{id} endpoint exists)
        mockMvc.perform(get("/api/v1/leads/contact/{contactId}", testContact.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + lead2.getId() + "')].paymentStatus",
                        contains("NONE")));
    }

    @Test
    void testContactWithNoLeads() throws Exception {
        Contact emptyContact = Contact.builder()
                .waId("9876543210")
                .name("Empty Contact")
                .source("WhatsApp")
                .owner(testUser)
                .build();
        emptyContact = contactRepository.save(emptyContact);

        mockMvc.perform(get("/api/v1/leads/contact/{contactId}", emptyContact.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        mockMvc.perform(get("/api/v1/leads/contact/{contactId}/latest", emptyContact.getId())
                        .header("Authorization", "Bearer " + authToken))
                // Then: 404 is returned because LeadService throws ResponseStatusException(NOT_FOUND)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("No lead found for this contact"));
    }

    @Test
    void testLeadPipelineWithMultipleLeadsPerContact() throws Exception {
        createTestLead(testContact, Lead.LeadStatus.NEW);
        createTestLead(testContact, Lead.LeadStatus.INTERESTED);
        createTestLead(testContact, Lead.LeadStatus.FOLLOW_UP);
        createTestLead(testContact, Lead.LeadStatus.BOOKED);
        createTestLead(testContact, Lead.LeadStatus.CLOSED_WON);

        mockMvc.perform(get("/api/v1/leads/status/NEW")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))));

        mockMvc.perform(get("/api/v1/leads/status/INTERESTED")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))));

        mockMvc.perform(get("/api/v1/leads/status/CLOSED_WON")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))));
    }

    @Test
    void testBackwardCompatibilityWithSingleLead() throws Exception {
        Lead singleLead = createTestLead(testContact, Lead.LeadStatus.INTERESTED);

        mockMvc.perform(get("/api/v1/leads/contact/{contactId}/latest", testContact.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", equalTo(singleLead.getId().toString())));

        mockMvc.perform(get("/api/v1/leads/contact/{contactId}", testContact.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id", equalTo(singleLead.getId().toString())));
    }

    @Test
    void testConcurrentLeadOperations() throws Exception {
        Lead lead1 = createTestLead(testContact, Lead.LeadStatus.NEW);
        Lead lead2 = createTestLead(testContact, Lead.LeadStatus.INTERESTED);

        mockMvc.perform(patch("/api/v1/leads/{leadId}/status", lead1.getId())
                        .header("Authorization", "Bearer " + authToken)
                        .param("status", "FOLLOW_UP"))
                .andExpect(status().isOk());

        String enquiryRequest = """
            {
                "message": "Follow up question",
                "type": "FOLLOW_UP",
                "source": "API Test"
            }
            """;

        mockMvc.perform(post("/api/v1/leads/{leadId}/enquiries", lead2.getId())
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(enquiryRequest))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/leads/contact/{contactId}", testContact.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + lead1.getId() + "')].status",
                        contains("FOLLOW_UP")));

        mockMvc.perform(get("/api/v1/leads/{leadId}/enquiries", lead2.getId())
                        .header("Authorization", "Bearer " + authToken))
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