package com.chatcrmlite.backend.integration;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.LeadRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import com.chatcrmlite.backend.services.LeadService;
import com.chatcrmlite.backend.services.WhatsAppService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for WhatsApp flow with multiple leads per contact functionality.
 * Tests the complete flow from webhook processing to lead creation.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class WhatsAppMultipleLeadsIntegrationTest {

    @Autowired
    private WhatsAppService whatsAppService;

    @Autowired
    private LeadService leadService;

    @Autowired
    private LeadRepository leadRepository;

    @Autowired
    private ContactRepository contactRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WhatsAppConfigRepository whatsAppConfigRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private User testUser;
    private WhatsAppConfig testConfig;
    private final String TEST_PHONE_NUMBER_ID = "test_phone_123";
    private final String TEST_WA_ID = "1234567890";

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

        // Create WhatsApp config
        testConfig = WhatsAppConfig.builder()
                .phoneNumberId(TEST_PHONE_NUMBER_ID)
                .accessToken("test_token")
                .user(testUser)
                .welcomeMessage("Welcome to our service!")
                .build();
        testConfig = whatsAppConfigRepository.save(testConfig);
    }

    @Test
    void testNewEnquiryCreatesNewLead() throws Exception {
        // Arrange: Create webhook payload for new enquiry
        String webhookPayload = createWebhookPayload(TEST_WA_ID, "I need pricing information", "text");

        // Act: Process webhook
        whatsAppService.processWebhook(webhookPayload);

        // Assert: New lead created
        Contact contact = contactRepository.findByWaIdAndOwner(TEST_WA_ID, testUser).orElseThrow();
        List<Lead> leads = leadService.getLeadsByContactId(contact.getId(), testUser);
        
        assertThat(leads).hasSize(1);
        assertThat(leads.get(0).getStatus()).isEqualTo(Lead.LeadStatus.NEW);
        assertThat(leads.get(0).getContact().getWaId()).isEqualTo(TEST_WA_ID);
    }

    @Test
    void testMultipleEnquiriesCreateMultipleLeads() throws Exception {
        // Arrange & Act: Send multiple distinct enquiries
        String enquiry1 = createWebhookPayload(TEST_WA_ID, "I need pricing for service A", "text");
        String enquiry2 = createWebhookPayload(TEST_WA_ID, "What about service B pricing?", "text");
        String enquiry3 = createWebhookPayload(TEST_WA_ID, "Can I book an appointment?", "text");

        whatsAppService.processWebhook(enquiry1);
        Thread.sleep(100); // Ensure different timestamps
        whatsAppService.processWebhook(enquiry2);
        Thread.sleep(100);
        whatsAppService.processWebhook(enquiry3);

        // Assert: Multiple leads created for same contact
        Contact contact = contactRepository.findByWaIdAndOwner(TEST_WA_ID, testUser).orElseThrow();
        List<Lead> leads = leadService.getLeadsByContactId(contact.getId(), testUser);
        
        assertThat(leads).hasSize(3);
        assertThat(leads).allMatch(lead -> lead.getContact().getWaId().equals(TEST_WA_ID));
        assertThat(leads).allMatch(lead -> lead.getStatus() == Lead.LeadStatus.NEW);
    }

    @Test
    void testGreetingCreatesNewLead() throws Exception {
        // Arrange: Create webhook payload for greeting
        String webhookPayload = createWebhookPayload(TEST_WA_ID, "hi", "text");

        // Act: Process webhook
        whatsAppService.processWebhook(webhookPayload);

        // Assert: New lead created for greeting
        Contact contact = contactRepository.findByWaIdAndOwner(TEST_WA_ID, testUser).orElseThrow();
        List<Lead> leads = leadService.getLeadsByContactId(contact.getId(), testUser);
        
        assertThat(leads).hasSize(1);
        assertThat(leads.get(0).getStatus()).isEqualTo(Lead.LeadStatus.NEW);
    }

    @Test
    void testInteractiveSelectionCreatesNewLead() throws Exception {
        // Arrange: Create webhook payload for interactive selection
        String webhookPayload = createInteractiveWebhookPayload(TEST_WA_ID, "View Services", "view_services");

        // Act: Process webhook
        whatsAppService.processWebhook(webhookPayload);

        // Assert: New lead created for interactive selection
        Contact contact = contactRepository.findByWaIdAndOwner(TEST_WA_ID, testUser).orElseThrow();
        List<Lead> leads = leadService.getLeadsByContactId(contact.getId(), testUser);
        
        assertThat(leads).hasSize(1);
        assertThat(leads.get(0).getStatus()).isEqualTo(Lead.LeadStatus.NEW);
    }

    @Test
    void testOngoingConversationReusesActiveLead() throws Exception {
        // Arrange: Create initial lead
        Contact contact = Contact.builder()
                .waId(TEST_WA_ID)
                .name("Test User")
                .source("WhatsApp")
                .owner(testUser)
                .build();
        contact = contactRepository.save(contact);

        Lead existingLead = Lead.builder()
                .contact(contact)
                .status(Lead.LeadStatus.INTERESTED)
                .owner(testUser)
                .build();
        existingLead = leadRepository.save(existingLead);

        // Act: Send follow-up message (not a new enquiry keyword)
        String webhookPayload = createWebhookPayload(TEST_WA_ID, "yes, I'm still interested", "text");
        whatsAppService.processWebhook(webhookPayload);

        // Assert: No new lead created, existing lead reused
        List<Lead> leads = leadService.getLeadsByContactId(contact.getId(), testUser);
        assertThat(leads).hasSize(1);
        assertThat(leads.get(0).getId()).isEqualTo(existingLead.getId());
    }

    @Test
    void testClosedLeadAllowsNewLeadCreation() throws Exception {
        // Arrange: Create closed lead
        Contact contact = Contact.builder()
                .waId(TEST_WA_ID)
                .name("Test User")
                .source("WhatsApp")
                .owner(testUser)
                .build();
        contact = contactRepository.save(contact);

        Lead closedLead = Lead.builder()
                .contact(contact)
                .status(Lead.LeadStatus.CLOSED_WON)
                .owner(testUser)
                .build();
        closedLead = leadRepository.save(closedLead);

        // Act: Send new enquiry
        String webhookPayload = createWebhookPayload(TEST_WA_ID, "I need another service", "text");
        whatsAppService.processWebhook(webhookPayload);

        // Assert: New lead created alongside closed lead
        List<Lead> leads = leadService.getLeadsByContactId(contact.getId(), testUser);
        assertThat(leads).hasSize(2);
        
        // One closed, one new
        assertThat(leads).anyMatch(lead -> lead.getStatus() == Lead.LeadStatus.CLOSED_WON);
        assertThat(leads).anyMatch(lead -> lead.getStatus() == Lead.LeadStatus.NEW);
    }

    @Test
    void testLeadIndependenceInWhatsAppFlow() throws Exception {
        // Arrange: Create multiple leads
        Contact contact = Contact.builder()
                .waId(TEST_WA_ID)
                .name("Test User")
                .source("WhatsApp")
                .owner(testUser)
                .build();
        contact = contactRepository.save(contact);

        Lead lead1 = Lead.builder()
                .contact(contact)
                .status(Lead.LeadStatus.NEW)
                .owner(testUser)
                .build();
        Lead savedLead1 = leadRepository.save(lead1);

        Lead lead2 = Lead.builder()
                .contact(contact)
                .status(Lead.LeadStatus.INTERESTED)
                .owner(testUser)
                .build();
        Lead savedLead2 = leadRepository.save(lead2);

        // Act: Update one lead's status
        leadService.updateStatus(savedLead1.getId(), Lead.LeadStatus.FOLLOW_UP, testUser);

        // Assert: Other lead remains unchanged
        List<Lead> leads = leadService.getLeadsByContactId(contact.getId(), testUser);
        assertThat(leads).hasSize(2);
        
        Lead updatedLead1 = leads.stream()
                .filter(l -> l.getId().equals(savedLead1.getId()))
                .findFirst().orElseThrow();
        Lead unchangedLead2 = leads.stream()
                .filter(l -> l.getId().equals(savedLead2.getId()))
                .findFirst().orElseThrow();

        assertThat(updatedLead1.getStatus()).isEqualTo(Lead.LeadStatus.FOLLOW_UP);
        assertThat(unchangedLead2.getStatus()).isEqualTo(Lead.LeadStatus.INTERESTED);
    }

    @Test
    void testContactProfileShowsAllLeads() throws Exception {
        // Arrange: Create multiple leads for same contact
        String enquiry1 = createWebhookPayload(TEST_WA_ID, "pricing inquiry", "text");
        String enquiry2 = createWebhookPayload(TEST_WA_ID, "booking request", "text");
        String enquiry3 = createWebhookPayload(TEST_WA_ID, "service information", "text");

        whatsAppService.processWebhook(enquiry1);
        Thread.sleep(100);
        whatsAppService.processWebhook(enquiry2);
        Thread.sleep(100);
        whatsAppService.processWebhook(enquiry3);

        // Act: Get contact and all leads
        Contact contact = contactRepository.findByWaIdAndOwner(TEST_WA_ID, testUser).orElseThrow();
        List<Lead> allLeads = leadService.getLeadsByContactId(contact.getId(), testUser);
        Lead latestLead = leadService.getLatestLeadByContactId(contact.getId(), testUser);

        // Assert: All leads are accessible
        assertThat(allLeads).hasSize(3);
        assertThat(allLeads).contains(latestLead);
        assertThat(allLeads).allMatch(lead -> lead.getContact().getId().equals(contact.getId()));
    }

    // ── Helper Methods ──────────────────────────────────────────────────────

    private String createWebhookPayload(String waId, String messageText, String messageType) throws Exception {
        String payload = """
            {
              "entry": [{
                "changes": [{
                  "value": {
                    "metadata": {
                      "phone_number_id": "%s"
                    },
                    "messages": [{
                      "from": "%s",
                      "id": "msg_%d",
                      "timestamp": "%d",
                      "type": "%s",
                      "text": {
                        "body": "%s"
                      }
                    }],
                    "contacts": [{
                      "wa_id": "%s",
                      "profile": {
                        "name": "Test User"
                      }
                    }]
                  }
                }]
              }]
            }
            """.formatted(
                TEST_PHONE_NUMBER_ID,
                waId,
                System.currentTimeMillis(),
                System.currentTimeMillis() / 1000,
                messageType,
                messageText,
                waId
            );
        
        // Validate JSON
        objectMapper.readTree(payload);
        return payload;
    }

    private String createInteractiveWebhookPayload(String waId, String title, String selectionId) throws Exception {
        String payload = """
            {
              "entry": [{
                "changes": [{
                  "value": {
                    "metadata": {
                      "phone_number_id": "%s"
                    },
                    "messages": [{
                      "from": "%s",
                      "id": "msg_%d",
                      "timestamp": "%d",
                      "type": "interactive",
                      "interactive": {
                        "type": "list_reply",
                        "list_reply": {
                          "title": "%s",
                          "id": "%s"
                        }
                      }
                    }],
                    "contacts": [{
                      "wa_id": "%s",
                      "profile": {
                        "name": "Test User"
                      }
                    }]
                  }
                }]
              }]
            }
            """.formatted(
                TEST_PHONE_NUMBER_ID,
                waId,
                System.currentTimeMillis(),
                System.currentTimeMillis() / 1000,
                title,
                selectionId,
                waId
            );
        
        // Validate JSON
        objectMapper.readTree(payload);
        return payload;
    }
}