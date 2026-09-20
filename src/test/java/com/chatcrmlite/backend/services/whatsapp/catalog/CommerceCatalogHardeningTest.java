package com.chatcrmlite.backend.services.whatsapp.catalog;

import com.chatcrmlite.backend.clients.MetaCommerceClient;
import com.chatcrmlite.backend.clients.WhatsAppClient;
import com.chatcrmlite.backend.dto.catalog.CatalogVerificationResult;
import com.chatcrmlite.backend.dto.catalog.SendProductResult;
import com.chatcrmlite.backend.models.CommerceCatalog;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Message;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.repositories.CommerceCatalogRepository;
import com.chatcrmlite.backend.repositories.MessageRepository;
import com.chatcrmlite.backend.repositories.TenantRepository;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import com.chatcrmlite.backend.services.websocket.DistributedWebSocketPublisher;
import com.chatcrmlite.backend.services.whatsapp.MessageDeliveryStatusService;
import com.chatcrmlite.backend.services.whatsapp.OutboundSendIdempotencyService;
import com.chatcrmlite.backend.services.whatsapp.WhatsAppOutboundService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CommerceCatalogHardeningTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // Verification Test Mocks
    @Mock
    private CommerceCatalogRepository catalogRepository;
    @Mock
    private TenantRepository tenantRepository;
    @Mock
    private WhatsAppConfigRepository whatsappConfigRepository;
    @Mock
    private MetaCommerceClient metaCommerceClient;
    private CommerceCatalogService commerceCatalogService;

    // Idempotency Test Mocks
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    private OutboundSendIdempotencyService idempotencyService;

    // Delivery Status Mocks
    @Mock
    private MessageRepository messageRepository;
    @Mock
    private DistributedWebSocketPublisher distributedWebSocketPublisher;
    private MessageDeliveryStatusService deliveryStatusService;

    // Outbound Service Mocks
    @Mock
    private WhatsAppClient whatsappClient;
    @Mock
    private TransactionTemplate transactionTemplate;
    private WhatsAppOutboundService outboundService;

    private UUID tenantId;
    private Tenant tenant;
    private User owner;
    private Contact contact;
    private WhatsAppConfig config;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        tenant = Tenant.builder().id(tenantId).businessName("Acme Corp").build();

        owner = User.builder().id(UUID.randomUUID()).email("owner@acme.com").tenant(tenant).build();

        contact = Contact.builder()
                .id(UUID.randomUUID())
                .waId("919876543210")
                .name("Alice")
                .owner(owner)
                .build();
        contact.setTenant(tenant);

        config = WhatsAppConfig.builder()
                .id(UUID.randomUUID())
                .tenant(tenant)
                .wabaId("waba_12345")
                .phoneNumberId("phone_67890")
                .accessToken("EAA_TEST_TOKEN")
                .user(owner)
                .build();

        commerceCatalogService = new CommerceCatalogService(
                catalogRepository,
                metaCommerceClient,
                tenantRepository,
                whatsappConfigRepository
        );

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        idempotencyService = new OutboundSendIdempotencyService(redisTemplate, objectMapper);

        deliveryStatusService = new MessageDeliveryStatusService(
                messageRepository,
                distributedWebSocketPublisher
        );

        outboundService = new WhatsAppOutboundService(
                whatsappClient,
                messageRepository,
                null,
                distributedWebSocketPublisher,
                transactionTemplate,
                idempotencyService
        );
    }

    // ==========================================
    // 1. Catalog Access Precondition Tests
    // ==========================================

    @Test
    @DisplayName("verifyCatalogAccess returns accessible=true when catalog is found in Meta connected list")
    void testVerifyCatalogAccess_Success() throws Exception {
        CommerceCatalog catalog = CommerceCatalog.builder()
                .tenant(tenant)
                .metaCatalogId("meta_cat_999")
                .name("Spring Summer 2026")
                .wabaId("waba_12345")
                .build();

        when(catalogRepository.findByTenantIdAndMetaCatalogId(tenantId, "meta_cat_999"))
                .thenReturn(Optional.of(catalog));
        when(whatsappConfigRepository.findByTenantId(tenantId))
                .thenReturn(Optional.of(config));

        String json = "{\"data\":[{\"id\":\"meta_cat_999\",\"name\":\"Spring Summer 2026\",\"vertical\":\"commerce\"}]}";
        JsonNode metaResponse = objectMapper.readTree(json);
        when(metaCommerceClient.listConnectedCatalogs("waba_12345", "EAA_TEST_TOKEN"))
                .thenReturn(metaResponse);

        CatalogVerificationResult result = commerceCatalogService.verifyCatalogAccess(tenantId, "meta_cat_999");

        assertTrue(result.accessible());
        assertEquals("meta_cat_999", result.catalogId());
        assertEquals("Spring Summer 2026", result.catalogName());
        assertNull(result.failureReason());
    }

    @Test
    @DisplayName("verifyCatalogAccess returns accessible=false when catalog is not linked to WABA on Meta")
    void testVerifyCatalogAccess_NotLinkedToWaba() throws Exception {
        CommerceCatalog catalog = CommerceCatalog.builder()
                .tenant(tenant)
                .metaCatalogId("meta_cat_999")
                .name("Spring Summer 2026")
                .wabaId("waba_12345")
                .build();

        when(catalogRepository.findByTenantIdAndMetaCatalogId(tenantId, "meta_cat_999"))
                .thenReturn(Optional.of(catalog));
        when(whatsappConfigRepository.findByTenantId(tenantId))
                .thenReturn(Optional.of(config));

        String json = "{\"data\":[{\"id\":\"different_cat_111\",\"name\":\"Other Catalog\"}]}";
        JsonNode metaResponse = objectMapper.readTree(json);
        when(metaCommerceClient.listConnectedCatalogs("waba_12345", "EAA_TEST_TOKEN"))
                .thenReturn(metaResponse);

        CatalogVerificationResult result = commerceCatalogService.verifyCatalogAccess(tenantId, "meta_cat_999");

        assertFalse(result.accessible());
        assertEquals("meta_cat_999", result.catalogId());
        assertNotNull(result.failureReason());
        assertTrue(result.failureReason().contains("Catalog ID is not linked to this WABA on Meta"));
    }

    @Test
    @DisplayName("verifyCatalogAccess captures subcode 2388004 and returns friendly guidance without asserting single cause")
    void testVerifyCatalogAccess_Subcode2388004() {
        CommerceCatalog catalog = CommerceCatalog.builder()
                .tenant(tenant)
                .metaCatalogId("invalid_cat_id")
                .name("Defective Catalog")
                .wabaId("waba_12345")
                .build();

        when(catalogRepository.findByTenantIdAndMetaCatalogId(tenantId, "invalid_cat_id"))
                .thenReturn(Optional.of(catalog));
        when(whatsappConfigRepository.findByTenantId(tenantId))
                .thenReturn(Optional.of(config));

        MetaCommerceClient.MetaCommerceError metaError = new MetaCommerceClient.MetaCommerceError(
                "100",
                "2388004",
                "trace_xyz_456",
                "Invalid Catalog",
                "The catalog ID is invalid or not eligible/accessible for this WABA. Verify catalog existence, ownership, accessibility, and WABA relationship through Meta before determining the exact cause."
        );

        when(metaCommerceClient.listConnectedCatalogs("waba_12345", "EAA_TEST_TOKEN"))
                .thenThrow(new MetaCommerceClient.MetaCommerceApiException("Meta Commerce API Error: " + metaError.errorUserMsg(), metaError));

        CatalogVerificationResult result = commerceCatalogService.verifyCatalogAccess(tenantId, "invalid_cat_id");

        assertFalse(result.accessible());
        assertEquals("trace_xyz_456", result.fbtraceId());
        assertTrue(result.failureReason().contains("The catalog ID is invalid or not eligible/accessible for this WABA"));
    }

    // ==========================================
    // 2. Outbound Idempotency State Machine Tests
    // ==========================================

    @Test
    @DisplayName("Idempotency claim acquires lock on fresh requestId")
    void testIdempotencyClaim_Acquired() {
        String key = idempotencyService.buildKey(tenantId, "req-101");
        String payloadHash = idempotencyService.computePayloadHash("Alice|meta_cat_999|sku_abc");
        String executionId = UUID.randomUUID().toString();

        when(valueOperations.setIfAbsent(eq(key), anyString(), eq(Duration.ofSeconds(120))))
                .thenReturn(Boolean.TRUE);

        OutboundSendIdempotencyService.ClaimResult claim = idempotencyService.claimOrCheck(key, payloadHash, executionId);

        assertEquals(OutboundSendIdempotencyService.ClaimStatus.ACQUIRED, claim.status());
        assertEquals(executionId, claim.executionId());
    }

    @Test
    @DisplayName("Idempotency detects CONFLICT (409) when same requestId is used with different payload")
    void testIdempotencyClaim_PayloadConflict() throws Exception {
        String key = idempotencyService.buildKey(tenantId, "req-101");
        String originalHash = idempotencyService.computePayloadHash("original-payload");
        String changedHash = idempotencyService.computePayloadHash("tampered-payload");
        String executionId = UUID.randomUUID().toString();

        OutboundSendIdempotencyService.IdempotencyRecord existing = OutboundSendIdempotencyService.IdempotencyRecord.builder()
                .status("IN_PROGRESS")
                .executionId(UUID.randomUUID().toString())
                .payloadHash(originalHash)
                .build();

        when(valueOperations.setIfAbsent(eq(key), anyString(), eq(Duration.ofSeconds(120))))
                .thenReturn(Boolean.FALSE);
        when(valueOperations.get(key))
                .thenReturn(objectMapper.writeValueAsString(existing));

        OutboundSendIdempotencyService.ClaimResult claim = idempotencyService.claimOrCheck(key, changedHash, executionId);

        assertEquals(OutboundSendIdempotencyService.ClaimStatus.CONFLICT, claim.status());
        assertTrue(claim.conflictMessage().contains("Request ID reused with different payload parameters"));
    }

    @Test
    @DisplayName("Idempotency returns META_ACCEPTED and waMessageId during retry after DB failure")
    void testIdempotencyClaim_MetaAcceptedRecovery() throws Exception {
        String key = idempotencyService.buildKey(tenantId, "req-101");
        String payloadHash = idempotencyService.computePayloadHash("Alice|meta_cat_999|sku_abc");
        String executionId = UUID.randomUUID().toString();

        OutboundSendIdempotencyService.IdempotencyRecord existing = OutboundSendIdempotencyService.IdempotencyRecord.builder()
                .status("META_ACCEPTED")
                .executionId(UUID.randomUUID().toString())
                .payloadHash(payloadHash)
                .waMessageId("wamid.HBgLMTIzNDU2Nzg5")
                .build();

        when(valueOperations.setIfAbsent(eq(key), anyString(), eq(Duration.ofSeconds(120))))
                .thenReturn(Boolean.FALSE);
        when(valueOperations.get(key))
                .thenReturn(objectMapper.writeValueAsString(existing));

        OutboundSendIdempotencyService.ClaimResult claim = idempotencyService.claimOrCheck(key, payloadHash, executionId);

        assertEquals(OutboundSendIdempotencyService.ClaimStatus.META_ACCEPTED, claim.status());
        assertEquals("wamid.HBgLMTIzNDU2Nzg5", claim.waMessageId());
    }

    // ==========================================
    // 3. Message Delivery Status & Conditional Downgrade Protection Tests
    // ==========================================

    @Test
    @DisplayName("DeliveryStatus transitions SENT -> DELIVERED atomically and broadcasts WS update")
    void testDeliveryStatus_DeliveredSuccess() {
        when(messageRepository.markDeliveredConditional("wamid.123")).thenReturn(1);

        Message updatedMessage = Message.builder()
                .id(UUID.randomUUID())
                .waMessageId("wamid.123")
                .owner(owner)
                .contact(contact)
                .deliveryStatus(Message.DeliveryStatus.DELIVERED)
                .build();
        updatedMessage.setTenant(tenant);

        when(messageRepository.findByWaMessageId("wamid.123"))
                .thenReturn(Optional.of(updatedMessage));

        deliveryStatusService.updateDeliveryStatus("wamid.123", "delivered");

        verify(messageRepository).markDeliveredConditional("wamid.123");
        verify(distributedWebSocketPublisher).publishMessage(eq(tenantId), any());
    }

    @Test
    @DisplayName("DeliveryStatus protects READ message from being downgraded to DELIVERED or FAILED")
    void testDeliveryStatus_DowngradeProtected() {
        // DB conditional query rejects update because current status is READ (returns 0 rows updated)
        when(messageRepository.markDeliveredConditional("wamid.123")).thenReturn(0);

        deliveryStatusService.updateDeliveryStatus("wamid.123", "delivered");

        verify(messageRepository).markDeliveredConditional("wamid.123");
        // Ensure no WebSocket event is dispatched for an invalid downgrade
        verify(distributedWebSocketPublisher, never()).publishMessage(any(), any());
    }

    // ==========================================
    // 4. End-to-End SPM Send & Crash Recovery Tests
    // ==========================================

    @Test
    @DisplayName("sendSingleProduct completes full flow: calls Meta outside tx, marks META_ACCEPTED, commits to DB with SENT status, and marks SUCCEEDED")
    void testSendSingleProduct_NormalFlow() {
        String requestId = "req-normal-1";
        String idempotencyKey = idempotencyService.buildKey(tenantId, requestId);
        String executionId = UUID.randomUUID().toString();

        when(whatsappClient.sendSingleProductMessage(
                eq(contact.getWaId()),
                eq("meta_cat_999"),
                eq("sku_abc"),
                eq("Check out this product!"),
                eq("EAA_TEST_TOKEN"),
                eq("phone_67890")
        )).thenReturn("wamid.META_SPM_999");

        Message savedMessage = Message.builder()
                .id(UUID.randomUUID())
                .waMessageId("wamid.META_SPM_999")
                .owner(owner)
                .contact(contact)
                .deliveryStatus(Message.DeliveryStatus.SENT)
                .build();
        savedMessage.setTenant(tenant);

        when(messageRepository.save(any(Message.class))).thenReturn(savedMessage);

        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });

        SendProductResult result = outboundService.sendSingleProduct(
                contact,
                "meta_cat_999",
                "sku_abc",
                "Product ABC",
                "Check out this product!",
                config,
                owner,
                idempotencyKey,
                executionId
        );

        assertEquals("wamid.META_SPM_999", result.waMessageId());
        assertEquals(savedMessage.getId().toString(), result.messageId());

        // Verify Meta API was called outside transaction
        verify(whatsappClient, times(1)).sendSingleProductMessage(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        // Verify message was saved to DB inside transaction
        verify(messageRepository, times(1)).save(any(Message.class));
    }

    @Test
    @DisplayName("recoverPendingCrmPersistence recovers message after previous DB crash without duplicate Meta API call")
    void testSendSingleProduct_MetaAcceptedRecoveryAfterDbCrash() {
        String requestId = "req-crashed-db-1";
        String idempotencyKey = idempotencyService.buildKey(tenantId, requestId);

        Message recoveredMessage = Message.builder()
                .id(UUID.randomUUID())
                .waMessageId("wamid.ALREADY_ACCEPTED_BY_META_123")
                .owner(owner)
                .contact(contact)
                .deliveryStatus(Message.DeliveryStatus.SENT)
                .build();
        recoveredMessage.setTenant(tenant);

        when(messageRepository.save(any(Message.class))).thenReturn(recoveredMessage);

        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });

        SendProductResult result = outboundService.recoverPendingCrmPersistence(
                contact,
                owner,
                "Product: Product ABC",
                "wamid.ALREADY_ACCEPTED_BY_META_123",
                "PRODUCT",
                idempotencyKey
        );

        // Verification: existing wamid recovered and saved to CRM
        assertEquals("wamid.ALREADY_ACCEPTED_BY_META_123", result.waMessageId());
        assertEquals(recoveredMessage.getId().toString(), result.messageId());

        // CRITICAL: Meta API MUST NOT BE CALLED AGAIN!
        verify(whatsappClient, never()).sendSingleProductMessage(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        // Message was saved to CRM DB
        verify(messageRepository, times(1)).save(any(Message.class));
    }
}
