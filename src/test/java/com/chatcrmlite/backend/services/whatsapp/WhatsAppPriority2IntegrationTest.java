package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.whatsapp.WhatsAppUserPreferenceLedger;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.TenantRepository;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import com.chatcrmlite.backend.repositories.WhatsAppUserPreferenceLedgerRepository;
import com.chatcrmlite.backend.models.Message;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.services.workflow.ProcessingContext;
import com.chatcrmlite.backend.services.whatsapp.campaign.WhatsAppRecipientResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class WhatsAppPriority2IntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WhatsAppRecipientResolver recipientResolver = new WhatsAppRecipientResolver();

    @Mock
    private ContactRepository contactRepository;
    @Mock
    private WhatsAppUserPreferenceLedgerRepository preferenceLedgerRepository;
    @Mock
    private WhatsAppConfigRepository whatsappConfigRepository;
    @Mock
    private TenantRepository tenantRepository;
    @Mock
    private com.chatcrmlite.backend.repositories.MessageRepository messageRepository;
    @Mock
    private com.chatcrmlite.backend.repositories.ConversationStateRepository conversationStateRepository;
    @Mock
    private com.chatcrmlite.backend.services.IdempotencyService idempotencyService;
    @Mock
    private com.chatcrmlite.backend.services.websocket.DistributedWebSocketPublisher distributedWebSocketPublisher;

    private ContactIdentityService contactIdentityService;
    private WhatsAppPreferenceService preferenceService;
    private WhatsAppIngressService whatsappIngressService;

    private UUID tenantId;
    private Tenant tenant;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        tenant = new Tenant();
        tenant.setId(tenantId);

        contactIdentityService = new ContactIdentityService(contactRepository);
        preferenceService = new WhatsAppPreferenceService(
                preferenceLedgerRepository,
                contactRepository,
                whatsappConfigRepository,
                tenantRepository,
                objectMapper
        );
        whatsappIngressService = new WhatsAppIngressService(
                contactRepository,
                messageRepository,
                whatsappConfigRepository,
                conversationStateRepository,
                idempotencyService,
                distributedWebSocketPublisher,
                objectMapper
        );
    }

    // ==========================================
    // 1. BSUID RECIPIENT RESOLVER TESTS
    // ==========================================

    @Test
    @DisplayName("BSUID-only contact resolves to BSUID identity with no phone")
    void testResolveBsuidRecipient() {
        Contact contact = new Contact();
        contact.setBsuid("US.987654321");
        contact.setWaId(null);

        WhatsAppRecipientResolver.ResolvedRecipient resolved = recipientResolver.resolve(contact);

        assertTrue(resolved.isSendable());
        assertTrue(resolved.isBsuid());
        assertFalse(resolved.isPhone());
        assertEquals("US.987654321", resolved.bsuid());
        assertNull(resolved.phoneNumber());
        assertEquals(WhatsAppRecipientResolver.RecipientIdentityType.BSUID, resolved.identityType());
        assertEquals("US.987654321", resolved.value());
    }

    @Test
    @DisplayName("Parent-BSUID-only contact resolves to PARENT_BSUID identity")
    void testResolveParentBsuidRecipient() {
        Contact contact = new Contact();
        contact.setParentBsuid("US.ENT.98765");
        contact.setBsuid(null);
        contact.setWaId(null);

        WhatsAppRecipientResolver.ResolvedRecipient resolved = recipientResolver.resolve(contact);

        assertTrue(resolved.isSendable());
        assertTrue(resolved.isParentBsuid());
        assertFalse(resolved.isBsuid());
        assertFalse(resolved.isPhone());
        assertEquals("US.ENT.98765", resolved.parentBsuid());
        assertEquals("US.ENT.98765", resolved.getEffectiveBsuid());
        assertNull(resolved.phoneNumber());
        assertNull(resolved.bsuid());
        assertEquals(WhatsAppRecipientResolver.RecipientIdentityType.PARENT_BSUID, resolved.identityType());
        assertEquals("US.ENT.98765", resolved.value());
    }

    @Test
    @DisplayName("Phone contact resolves to PHONE identity with no BSUID")
    void testResolvePhoneRecipient() {
        Contact contact = new Contact();
        contact.setWaId("+15551234567");
        contact.setBsuid(null);

        WhatsAppRecipientResolver.ResolvedRecipient resolved = recipientResolver.resolve(contact);

        assertTrue(resolved.isSendable());
        assertTrue(resolved.isPhone());
        assertFalse(resolved.isBsuid());
        assertEquals("+15551234567", resolved.phoneNumber());
        assertNull(resolved.bsuid());
        assertEquals(WhatsAppRecipientResolver.RecipientIdentityType.PHONE, resolved.identityType());
        assertEquals("+15551234567", resolved.value());
    }

    @Test
    @DisplayName("Unsendable contact with neither phone nor BSUID")
    void testResolveUnsendableRecipient() {
        Contact contact = new Contact();
        contact.setWaId(null);
        contact.setBsuid(null);

        WhatsAppRecipientResolver.ResolvedRecipient resolved = recipientResolver.resolve(contact);

        assertFalse(resolved.isSendable());
        assertEquals(WhatsAppRecipientResolver.RecipientIdentityType.UNSENDABLE, resolved.identityType());
    }

    @Test
    @DisplayName("Gate 0: Auth template excluding BSUID marks BSUID-only contact as UNSENDABLE")
    void testAuthTemplateGate0ExcludesBsuidOnlyRecipient() {
        Contact contact = new Contact();
        contact.setBsuid("US.987654321");
        contact.setWaId(null);

        // Template requires phone (e.g. AUTHENTICATION / one-tap / copy-code)
        WhatsAppRecipientResolver.ResolvedRecipient resolved = recipientResolver.resolve(contact, null, true);

        assertFalse(resolved.isSendable());
        assertEquals(WhatsAppRecipientResolver.RecipientIdentityType.UNSENDABLE, resolved.identityType());
    }

    @Test
    @DisplayName("Gate 0: Auth template excluding BSUID allows phone-verified recipient")
    void testAuthTemplateGate0AllowsPhoneRecipient() {
        Contact contact = new Contact();
        contact.setBsuid("US.987654321");
        contact.setWaId("+15551234567");

        // Verified phone is selected even if BSUID is present
        WhatsAppRecipientResolver.ResolvedRecipient resolved = recipientResolver.resolve(contact, null, true);

        assertTrue(resolved.isSendable());
        assertTrue(resolved.isPhone());
        assertEquals("+15551234567", resolved.phoneNumber());
        assertEquals(WhatsAppRecipientResolver.RecipientIdentityType.PHONE, resolved.identityType());
    }

    @Test
    @DisplayName("Inbound message with from=null, from_user_id and from_parent_user_id resolves contact by BSUID and links parent BSUID")
    void testInboundBsuidOnlyIdentityResolutionRetainsConversation() throws Exception {
        String payload = """
        {
            "entry": [
                {
                    "id": "WABA_123",
                    "changes": [
                        {
                            "field": "messages",
                            "value": {
                                "metadata": { "phone_number_id": "PN_123" },
                                "contacts": [
                                    {
                                        "user_id": "US.uniqueBsuid999",
                                        "parent_user_id": "US.ENT.parent999",
                                        "profile": { "name": "BSUID Customer" }
                                    }
                                ],
                                "messages": [
                                    {
                                        "id": "WAMID_INBOUND_BSUID_01",
                                        "from": null,
                                        "from_user_id": "US.uniqueBsuid999",
                                        "from_parent_user_id": "US.ENT.parent999",
                                        "type": "text",
                                        "text": { "body": "Hello CRM from BSUID user" }
                                    }
                                ]
                            }
                        }
                    ]
                }
            ]
        }
        """;

        Contact existingContact = new Contact();
        existingContact.setId(UUID.randomUUID());
        existingContact.setBsuid("US.uniqueBsuid999");
        existingContact.setWaId(null);
        existingContact.setName("BSUID Customer");

        WhatsAppConfig config = new WhatsAppConfig();
        config.setTenant(tenant);

        when(idempotencyService.markAsProcessing("WAMID_INBOUND_BSUID_01", tenantId)).thenReturn(true);
        when(whatsappConfigRepository.findByTenantId(tenantId)).thenReturn(Optional.of(config));
        when(contactRepository.findByTenantIdAndBsuid(tenantId, "US.uniqueBsuid999")).thenReturn(Optional.of(existingContact));

        ProcessingContext context = ProcessingContext.builder()
                .messageId("WAMID_INBOUND_BSUID_01")
                .waId("US.uniqueBsuid999")
                .tenantId(tenantId)
                .payload(payload)
                .timestamp(System.currentTimeMillis())
                .currentStage(ProcessingContext.WorkflowStage.INGRESS)
                .build();

        whatsappIngressService.resolveAndSaveIngress(context);

        // Verify existing contact was used
        assertEquals(existingContact.getId(), context.getMetadata().get("contactId"));
        // Verify Parent BSUID was linked to contact
        assertEquals("US.ENT.parent999", existingContact.getParentBsuid());
        // Verify message was attached to the existing contact
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).save(messageCaptor.capture());
        assertEquals("WAMID_INBOUND_BSUID_01", messageCaptor.getValue().getWaMessageId());
        assertEquals(existingContact, messageCaptor.getValue().getContact());
        assertEquals("Hello CRM from BSUID user", messageCaptor.getValue().getContent());
    }

    // ==========================================
    // 2. BSUID SYSTEM MESSAGE TESTS
    // ==========================================

    @Test
    @DisplayName("Handle user_changed_number system message migrates both phone and BSUID")
    void testSystemMessageUserChangedNumber() throws Exception {
        String json = """
        {
            "from": "15550001111",
            "type": "system",
            "system": {
                "type": "user_changed_number",
                "wa_id": "15550001111",
                "new_wa_id": "15559998888",
                "user_id": "US.newBsuid123"
            }
        }
        """;
        JsonNode msgNode = objectMapper.readTree(json);

        Contact contact = new Contact();
        contact.setId(UUID.randomUUID());
        contact.setWaId("15550001111");

        when(contactRepository.findByTenantIdAndWaId(tenantId, "15550001111"))
                .thenReturn(Optional.of(contact));

        contactIdentityService.processSystemMessage(msgNode, tenantId, null);

        assertEquals("15559998888", contact.getWaId());
        assertEquals("US.newBsuid123", contact.getBsuid());
        verify(contactRepository).save(contact);
    }

    @Test
    @DisplayName("Handle user_changed_user_id system message migrates BSUID")
    void testSystemMessageUserChangedUserId() throws Exception {
        String json = """
        {
            "from": "15550001111",
            "type": "system",
            "system": {
                "type": "user_changed_user_id",
                "user_id": "US.oldBsuid",
                "new_user_id": "US.newMigratedBsuid"
            }
        }
        """;
        JsonNode msgNode = objectMapper.readTree(json);

        Contact contact = new Contact();
        contact.setId(UUID.randomUUID());
        contact.setWaId("15550001111");
        contact.setBsuid("US.oldBsuid");

        when(contactRepository.findByTenantIdAndWaId(tenantId, "15550001111"))
                .thenReturn(Optional.of(contact));

        contactIdentityService.processSystemMessage(msgNode, tenantId, null);

        assertEquals("US.newMigratedBsuid", contact.getBsuid());
        verify(contactRepository).save(contact);
    }

    @Test
    @DisplayName("Handle user_identity_changed system message updates identity")
    void testSystemMessageUserIdentityChanged() throws Exception {
        String json = """
        {
            "from": "15550001111",
            "type": "system",
            "system": {
                "type": "user_identity_changed",
                "new_wa_id": "15554443333",
                "new_user_id": "US.identityBsuid"
            }
        }
        """;
        JsonNode msgNode = objectMapper.readTree(json);

        Contact contact = new Contact();
        contact.setId(UUID.randomUUID());
        contact.setWaId("15550001111");

        when(contactRepository.findByTenantIdAndWaId(tenantId, "15550001111"))
                .thenReturn(Optional.of(contact));

        contactIdentityService.processSystemMessage(msgNode, tenantId, null);

        assertEquals("15554443333", contact.getWaId());
        assertEquals("US.identityBsuid", contact.getBsuid());
        verify(contactRepository).save(contact);
    }

    @Test
    @DisplayName("user_identity_changed is non-mutating by default when schema is unrecognized")
    void testSystemMessageUserIdentityChangedUnrecognizedIsNonMutating() throws Exception {
        String json = """
        {
            "from": "15550001111",
            "type": "system",
            "system": {
                "type": "user_identity_changed",
                "custom_unrecognized_field": "xyz123"
            }
        }
        """;
        JsonNode msgNode = objectMapper.readTree(json);

        assertDoesNotThrow(() -> contactIdentityService.processSystemMessage(msgNode, tenantId, null));
        // Verify no contact mutation occurred
        verify(contactRepository, never()).save(any());
    }

    @Test
    @DisplayName("Preserve unknown system event types gracefully without throwing")
    void testSystemMessageUnknownType() throws Exception {
        String json = """
        {
            "from": "15550001111",
            "type": "system",
            "system": {
                "type": "future_unknown_system_event",
                "meta_data": "some_value"
            }
        }
        """;
        JsonNode msgNode = objectMapper.readTree(json);

        assertDoesNotThrow(() -> contactIdentityService.processSystemMessage(msgNode, tenantId, null));
        verify(contactRepository, never()).save(any());
    }

    // ==========================================
    // 3. CANONICAL SORTED JSON FINGERPRINT TESTS
    // ==========================================

    @Test
    @DisplayName("Canonical JSON produces identical fingerprints regardless of key ordering")
    void testCanonicalJsonFingerprintEquivalence() throws Exception {
        String json1 = "{\"category\":\"marketing_messages\",\"value\":\"stop\",\"timestamp\":1750030073}";
        String json2 = "{\"timestamp\":1750030073,\"value\":\"stop\",\"category\":\"marketing_messages\"}";

        JsonNode node1 = objectMapper.readTree(json1);
        JsonNode node2 = objectMapper.readTree(json2);

        String canon1 = preferenceService.canonicalizeJson(node1);
        String canon2 = preferenceService.canonicalizeJson(node2);

        assertEquals(canon1, canon2);

        String hash1 = WhatsAppPreferenceService.generateSha256("tenant:waba:phone:" + canon1);
        String hash2 = WhatsAppPreferenceService.generateSha256("tenant:waba:phone:" + canon2);

        assertEquals(hash1, hash2);
    }

    // ==========================================
    // 4. USER_PREFERENCES OPT-OUT & RESUME TESTS
    // ==========================================

    @Test
    @DisplayName("user_preferences STOP sets marketingOptedOut to true")
    void testUserPreferencesStop() throws Exception {
        String json = """
        {
            "id": "WABA_123",
            "changes": [
                {
                    "field": "user_preferences",
                    "value": {
                        "metadata": { "phone_number_id": "PN_123" },
                        "user_preferences": [
                            {
                                "wa_id": "15557778888",
                                "category": "marketing_messages",
                                "value": "stop",
                                "timestamp": 1750030073
                            }
                        ]
                    }
                }
            ]
        }
        """;
        JsonNode root = objectMapper.readTree(json);
        JsonNode entry = root;
        JsonNode change = root.path("changes").get(0);

        when(whatsappConfigRepository.findTenantIdByPhoneNumberId("PN_123")).thenReturn(Optional.of(tenantId));
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(preferenceLedgerRepository.existsByTenantIdAndCanonicalFingerprint(eq(tenantId), anyString())).thenReturn(false);

        Contact contact = new Contact();
        contact.setId(UUID.randomUUID());
        contact.setWaId("15557778888");
        contact.setMarketingOptedOut(false);

        when(contactRepository.findByTenantIdAndWaId(tenantId, "15557778888")).thenReturn(Optional.of(contact));

        preferenceService.handleUserPreferences(entry, change, Instant.now());

        assertTrue(contact.isMarketingOptedOut());
        assertEquals("WHATSAPP_NATIVE_PREFERENCE", contact.getMarketingOptOutSource());
        assertEquals(Instant.ofEpochSecond(1750030073L), contact.getMarketingPreferenceAt());
        verify(contactRepository).save(contact);
        verify(preferenceLedgerRepository).save(any(WhatsAppUserPreferenceLedger.class));
    }

    @Test
    @DisplayName("user_preferences RESUME clears marketingOptedOut")
    void testUserPreferencesResume() throws Exception {
        String json = """
        {
            "id": "WABA_123",
            "changes": [
                {
                    "field": "user_preferences",
                    "value": {
                        "metadata": { "phone_number_id": "PN_123" },
                        "user_preferences": [
                            {
                                "wa_id": "15557778888",
                                "category": "marketing_messages",
                                "value": "resume",
                                "timestamp": 1750030080
                            }
                        ]
                    }
                }
            ]
        }
        """;
        JsonNode root = objectMapper.readTree(json);
        JsonNode entry = root;
        JsonNode change = root.path("changes").get(0);

        when(whatsappConfigRepository.findTenantIdByPhoneNumberId("PN_123")).thenReturn(Optional.of(tenantId));
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(preferenceLedgerRepository.existsByTenantIdAndCanonicalFingerprint(eq(tenantId), anyString())).thenReturn(false);

        Contact contact = new Contact();
        contact.setId(UUID.randomUUID());
        contact.setWaId("15557778888");
        contact.setMarketingOptedOut(true);
        contact.setMarketingPreferenceAt(Instant.ofEpochSecond(1750030073L));

        when(contactRepository.findByTenantIdAndWaId(tenantId, "15557778888")).thenReturn(Optional.of(contact));

        preferenceService.handleUserPreferences(entry, change, Instant.now());

        assertFalse(contact.isMarketingOptedOut());
        assertEquals("WHATSAPP_NATIVE_PREFERENCE_RESUME", contact.getMarketingOptOutSource());
        assertEquals(Instant.ofEpochSecond(1750030080L), contact.getMarketingPreferenceAt());
        verify(contactRepository).save(contact);
    }

    @Test
    @DisplayName("Unknown preference category preserved in ledger without altering marketing suppression")
    void testUnknownPreferenceCategoryPreserved() throws Exception {
        String json = """
        {
            "id": "WABA_123",
            "changes": [
                {
                    "field": "user_preferences",
                    "value": {
                        "metadata": { "phone_number_id": "PN_123" },
                        "user_preferences": [
                            {
                                "wa_id": "15557778888",
                                "category": "transactional_alerts",
                                "value": "mute",
                                "timestamp": 1750030090
                            }
                        ]
                    }
                }
            ]
        }
        """;
        JsonNode root = objectMapper.readTree(json);
        JsonNode entry = root;
        JsonNode change = root.path("changes").get(0);

        when(whatsappConfigRepository.findTenantIdByPhoneNumberId("PN_123")).thenReturn(Optional.of(tenantId));
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(preferenceLedgerRepository.existsByTenantIdAndCanonicalFingerprint(eq(tenantId), anyString())).thenReturn(false);

        preferenceService.handleUserPreferences(entry, change, Instant.now());

        // Contact suppression was NOT altered
        verify(contactRepository, never()).save(any());
        // But ledger captured the event
        ArgumentCaptor<WhatsAppUserPreferenceLedger> ledgerCaptor = ArgumentCaptor.forClass(WhatsAppUserPreferenceLedger.class);
        verify(preferenceLedgerRepository).save(ledgerCaptor.capture());
        assertEquals("transactional_alerts", ledgerCaptor.getValue().getCategory());
        assertEquals("mute", ledgerCaptor.getValue().getPreferenceValue());
    }

    @Test
    @DisplayName("Out-of-order user_preferences event with earlier timestamp is ignored for marketing suppression")
    void testUserPreferencesOutOfOrderIgnored() throws Exception {
        String json = """
        {
            "id": "WABA_123",
            "changes": [
                {
                    "field": "user_preferences",
                    "value": {
                        "metadata": { "phone_number_id": "PN_123" },
                        "user_preferences": [
                            {
                                "wa_id": "15557778888",
                                "category": "marketing_messages",
                                "value": "resume",
                                "timestamp": 1750030050
                            }
                        ]
                    }
                }
            ]
        }
        """;
        JsonNode root = objectMapper.readTree(json);
        JsonNode entry = root;
        JsonNode change = root.path("changes").get(0);

        when(whatsappConfigRepository.findTenantIdByPhoneNumberId("PN_123")).thenReturn(Optional.of(tenantId));
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(preferenceLedgerRepository.existsByTenantIdAndCanonicalFingerprint(eq(tenantId), anyString())).thenReturn(false);

        Contact contact = new Contact();
        contact.setId(UUID.randomUUID());
        contact.setWaId("15557778888");
        contact.setMarketingOptedOut(true);
        // Current preference timestamp is newer (1750030073 > 1750030050)
        contact.setMarketingPreferenceAt(Instant.ofEpochSecond(1750030073L));

        when(contactRepository.findByTenantIdAndWaId(tenantId, "15557778888")).thenReturn(Optional.of(contact));

        preferenceService.handleUserPreferences(entry, change, Instant.now());

        // Contact suppression was NOT altered because event timestamp is before contact's marketingPreferenceAt
        verify(contactRepository, never()).save(any());
        assertTrue(contact.isMarketingOptedOut());
        // But ledger captured the event
        verify(preferenceLedgerRepository).save(any(WhatsAppUserPreferenceLedger.class));
    }
}
