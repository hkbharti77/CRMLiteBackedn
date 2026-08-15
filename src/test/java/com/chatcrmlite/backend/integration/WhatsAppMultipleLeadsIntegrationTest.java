package com.chatcrmlite.backend.integration;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.LeadRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import com.chatcrmlite.backend.services.lead.LeadService;
import com.chatcrmlite.backend.repositories.MessageRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

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
    private MessageRepository messageRepository;

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

    private void processWebhook(String payload) throws Exception {
        JsonNode root = objectMapper.readTree(payload);
        JsonNode entry = root.path("entry").get(0);
        JsonNode change = entry.path("changes").get(0);
        JsonNode value = change.path("value");
        
        String phoneNumberId = value.path("metadata").path("phone_number_id").asText();
        
        JsonNode messageNode = value.path("messages").get(0);
        String waId = messageNode.path("from").asText();
        String text = "Media / Unsupported";
        String type = messageNode.path("type").asText();
        String selectionId = null;
        
        if ("text".equals(type)) {
            text = messageNode.path("text").path("body").asText();
        } else if ("interactive".equals(type)) {
            JsonNode interactive = messageNode.path("interactive");
            selectionId = interactive.path(interactive.path("type").asText()).path("id").asText();
            text = interactive.path(interactive.path("type").asText()).path("title").asText();
        }
        
        JsonNode contacts = value.path("contacts");
        final String finalProfileName = (contacts != null && contacts.isArray() && contacts.size() > 0)
                ? contacts.get(0).path("profile").path("name").asText()
                : null;

        WhatsAppConfig config = whatsAppConfigRepository.findByPhoneNumberId(phoneNumberId)
                .orElseThrow(() -> new RuntimeException("WhatsApp config not found"));
        User owner = config.getUser();
        
        Contact contact = contactRepository.findByWaIdAndOwner(waId, owner)
                .orElseGet(() -> {
                    Contact newContact = Contact.builder()
                            .waId(waId)
                            .name(finalProfileName != null ? finalProfileName : "WhatsApp User " + waId)
                            .source("WhatsApp")
                            .owner(owner)
                            .build();
                    return contactRepository.save(newContact);
                });

        com.chatcrmlite.backend.models.Message message = com.chatcrmlite.backend.models.Message.builder()
                .contact(contact)
                .owner(owner)
                .waMessageId("test-msg-" + System.nanoTime())
                .content(text)
                .direction(com.chatcrmlite.backend.models.Message.Direction.INCOMING)
                .timestamp(java.time.LocalDateTime.now())
                .build();
        messageRepository.save(message);

        Optional<Lead> latestLeadOpt = leadRepository.findTopByContactOrderByCreatedAtDesc(contact);
        if (latestLeadOpt.isEmpty() || latestLeadOpt.get().getStatus() == Lead.LeadStatus.CLOSED_WON || latestLeadOpt.get().getStatus() == Lead.LeadStatus.CLOSED_LOST) {
            String lower = text.trim().toLowerCase();
            boolean isNewEnquiry = lower.contains("pricing") || lower.contains(" pricing ") || lower.contains("book") || lower.contains("another service");
            boolean isGreeting = lower.matches("^(hi|hello|hey|namaste|hi there|hello there)$");
            boolean isInteractiveTrigger = "interactive".equals(type) && "view_services".equals(selectionId);

            if (isNewEnquiry || isGreeting || isInteractiveTrigger) {
                String enquiryType = isNewEnquiry ? "NEW_ENQUIRY" : "ONGOING";
                
                leadService.validateLeadCreation(contact, owner, enquiryType);

                Lead lead = Lead.builder()
                        .contact(contact)
                        .owner(owner)
                        .status(Lead.LeadStatus.NEW)
                        .build();
                leadRepository.save(lead);

                leadService.appendEnquiryToLead(lead, text, "CHAT", "WhatsApp Ingress", null);
            }
        }
    }

    @Test
    void testNewEnquiryCreatesNewLead() throws Exception {
        // Arrange: Create webhook payload for new enquiry
        String webhookPayload = createWebhookPayload(TEST_WA_ID, "I need pricing information", "text");

        // Act: Process webhook
        processWebhook(webhookPayload);

        // Assert: New lead created
        Contact contact = contactRepository.findByWaIdAndOwner(TEST_WA_ID, testUser).orElseThrow();
        List<Lead> leads = leadService.getLeadsByContactId(contact.getId(), testUser);
        
        assertThat(leads).hasSize(1);
        assertThat(leads.get(0).getStatus()).isEqualTo(Lead.LeadStatus.NEW);
        assertThat(leads.get(0).getContact().getWaId()).isEqualTo(TEST_WA_ID);
    }

    @Test
    void testMultipleEnquiriesCreateMultipleLeads() throws Exception {
        // Arrange & Act: Send multiple distinct enquiries, closing the lead in between
        String enquiry1 = createWebhookPayload(TEST_WA_ID, "I need pricing for service A", "text");
        String enquiry2 = createWebhookPayload(TEST_WA_ID, "What about service B pricing?", "text");
        String enquiry3 = createWebhookPayload(TEST_WA_ID, "Can I book an appointment?", "text");

        processWebhook(enquiry1);
        
        // Close the first lead so the next enquiry creates a new lead
        Contact contact = contactRepository.findByWaIdAndOwner(TEST_WA_ID, testUser).orElseThrow();
        Lead lead1 = leadService.getLatestLeadByContactId(contact.getId(), testUser);
        leadService.updateStatus(lead1.getId(), Lead.LeadStatus.CLOSED_WON, null, null, null, null, null, null, testUser);

        Thread.sleep(100); // Ensure different timestamps
        processWebhook(enquiry2);

        // Close the second lead
        Lead lead2 = leadService.getLatestLeadByContactId(contact.getId(), testUser);
        leadService.updateStatus(lead2.getId(), Lead.LeadStatus.CLOSED_LOST, null, null, null, null, null, null, testUser);

        Thread.sleep(100);
        processWebhook(enquiry3);

        // Assert: Multiple leads created for same contact
        List<Lead> leads = leadService.getLeadsByContactId(contact.getId(), testUser);
        
        assertThat(leads).hasSize(3);
        assertThat(leads).allMatch(lead -> lead.getContact().getWaId().equals(TEST_WA_ID));
    }

    @Test
    void testGreetingCreatesNewLead() throws Exception {
        // Arrange: Create webhook payload for greeting
        String webhookPayload = createWebhookPayload(TEST_WA_ID, "hi", "text");

        // Act: Process webhook
        processWebhook(webhookPayload);

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
        processWebhook(webhookPayload);

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
        processWebhook(webhookPayload);

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
        processWebhook(webhookPayload);

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
        leadService.updateStatus(savedLead1.getId(), Lead.LeadStatus.FOLLOW_UP, null, null, null, null, null, null, testUser);

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
        String enquiry3 = createWebhookPayload(TEST_WA_ID, "pricing information", "text");

        processWebhook(enquiry1);
        
        Contact contact = contactRepository.findByWaIdAndOwner(TEST_WA_ID, testUser).orElseThrow();
        Lead lead1 = leadService.getLatestLeadByContactId(contact.getId(), testUser);
        leadService.updateStatus(lead1.getId(), Lead.LeadStatus.CLOSED_WON, null, null, null, null, null, null, testUser);

        Thread.sleep(100);
        processWebhook(enquiry2);

        Lead lead2 = leadService.getLatestLeadByContactId(contact.getId(), testUser);
        leadService.updateStatus(lead2.getId(), Lead.LeadStatus.CLOSED_LOST, null, null, null, null, null, null, testUser);

        Thread.sleep(100);
        processWebhook(enquiry3);

        // Act: Get contact and all leads
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