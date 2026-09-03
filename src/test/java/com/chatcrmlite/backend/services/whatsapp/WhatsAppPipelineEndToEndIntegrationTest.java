package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.MessageRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import com.chatcrmlite.backend.services.DeadLetterHandler;
import com.chatcrmlite.backend.services.memory.ConversationMemoryService;
import com.chatcrmlite.backend.services.RagRetrievalService;
import com.chatcrmlite.backend.services.RedisStateService;
import com.chatcrmlite.backend.services.WebhookWorker;
import com.chatcrmlite.backend.services.ai.guardrail.GuardrailService;
import com.chatcrmlite.backend.dto.ai.Decision;
import com.chatcrmlite.backend.dto.ai.GuardrailResult;
import com.chatcrmlite.backend.services.tenant.TenantResourceManager;
import com.chatcrmlite.backend.services.workflow.AIWorker;
import com.chatcrmlite.backend.services.workflow.DeliveryWorker;
import com.chatcrmlite.backend.services.workflow.ProcessingContext;
import com.chatcrmlite.backend.services.workflow.QueueRouter;
import com.chatcrmlite.backend.services.workflow.WorkflowOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.connection.stream.ObjectRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WhatsAppPipelineEndToEndIntegrationTest {

    @Mock
    private WhatsAppConfigRepository whatsappConfigRepository;

    @Mock
    private ContactRepository contactRepository;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private GuardrailService guardrailService;

    @Mock
    private RagRetrievalService ragRetrievalService;

    @Mock
    private ConversationMemoryService conversationMemoryService;

    @Mock
    private WhatsAppMessageService messageService;

    @Mock
    private WhatsAppMenuService menuService;

    @Mock
    private WorkflowOrchestrator workflowOrchestrator;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private StringRedisTemplate redisTemplate;

    @Mock
    private DeadLetterHandler deadLetterHandler;

    @Mock
    private TenantResourceManager resourceManager;

    @Mock
    private RedisStateService redisStateService;

    @Mock
    private com.chatcrmlite.backend.analytics.AnalyticsEmitter analyticsEmitter;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private WebhookWorker webhookWorker;
    private WhatsAppAiService whatsappAiService;
    private AIWorker aiWorker;
    private WhatsAppDeliveryHandler deliveryHandler;
    private DeliveryWorker deliveryWorker;

    private UUID tenantId;
    private Tenant tenant;
    private User owner;
    private WhatsAppConfig config;
    private Contact contact;
    private final String phoneNumberId = "109876543210";
    private final String waId = "919999999999";
    private final String waMessageId = "wamid.HBgLM...A==";

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        tenant = Tenant.builder().id(tenantId).businessName("Acme Corp").build();

        owner = User.builder()
                .id(UUID.randomUUID())
                .email("owner@acme.com")
                .role(User.Role.OWNER)
                .tenant(tenant)
                .businessSubType("TECH_SUPPORT")
                .build();

        java.util.Set<User> tenantUsers = new java.util.HashSet<>();
        tenantUsers.add(owner);
        tenant.setUsers(tenantUsers);

        config = WhatsAppConfig.builder()
                .id(UUID.randomUUID())
                .tenant(tenant)
                .user(owner)
                .phoneNumberId(phoneNumberId)
                .accessToken("EAAX...")
                .build();
        config.setUser(owner);

        contact = Contact.builder()
                .id(UUID.randomUUID())
                .waId(waId)
                .name("Alice")
                .owner(owner)
                .build();
        contact.setTenant(tenant);

        // 1. Setup WebhookWorker
        webhookWorker = new WebhookWorker(
                workflowOrchestrator,
                whatsappConfigRepository,
                objectMapper,
                redisTemplate,
                deadLetterHandler,
                resourceManager,
                null,
                null
        );
        ReflectionTestUtils.setField(webhookWorker, "redisStateService", redisStateService);
        ReflectionTestUtils.setField(webhookWorker, "streamName", "whatsapp:ingress:stream");
        ReflectionTestUtils.setField(webhookWorker, "groupName", "whatsapp-workers");
        ReflectionTestUtils.setField(webhookWorker, "maxRetries", 3);

        // 2. Setup WhatsAppAiService & AIWorker
        whatsappAiService = new WhatsAppAiService(
                whatsappConfigRepository,
                contactRepository,
                messageRepository,
                guardrailService,
                ragRetrievalService,
                conversationMemoryService
        );
        ReflectionTestUtils.setField(whatsappAiService, "userRepository", userRepository);

        aiWorker = new AIWorker(
                workflowOrchestrator,
                whatsappAiService,
                redisTemplate,
                deadLetterHandler,
                redisStateService,
                resourceManager,
                analyticsEmitter,
                objectMapper
        );
        ReflectionTestUtils.setField(aiWorker, "groupName", "whatsapp-workers");

        // 3. Setup WhatsAppDeliveryHandler & DeliveryWorker
        deliveryHandler = new WhatsAppDeliveryHandler(
                whatsappConfigRepository,
                contactRepository,
                messageService,
                menuService
        );
        ReflectionTestUtils.setField(deliveryHandler, "userRepository", userRepository);

        deliveryWorker = new DeliveryWorker(
                workflowOrchestrator,
                null,
                whatsappConfigRepository,
                redisTemplate,
                deliveryHandler,
                objectMapper
        );
        ReflectionTestUtils.setField(deliveryWorker, "dlqHandler", deadLetterHandler);
        ReflectionTestUtils.setField(deliveryWorker, "groupName", "whatsapp-workers");
    }

    @Test
    @DisplayName("End-to-End Pipeline: Ingress -> AI -> Delivery succeeds with full acknowledgment")
    void fullPipelineFlow_IngressToDelivery_Succeeds() throws Exception {
        // Stage 1: Ingress Webhook Payload
        String webhookJson = """
                {
                  "object": "whatsapp_business_account",
                  "entry": [
                    {
                      "id": "waba_123",
                      "changes": [
                        {
                          "field": "messages",
                          "value": {
                            "messaging_product": "whatsapp",
                            "metadata": {
                              "display_phone_number": "15550234567",
                              "phone_number_id": "%s"
                            },
                            "contacts": [
                              { "profile": { "name": "Alice" }, "wa_id": "%s" }
                            ],
                            "messages": [
                              {
                                "from": "%s",
                                "id": "%s",
                                "timestamp": "1779381961",
                                "text": { "body": "What are your business hours?" },
                                "type": "text"
                              }
                            ]
                          }
                        }
                      ]
                    }
                  ]
                }
                """.formatted(phoneNumberId, waId, waId, waMessageId);

        ObjectRecord<String, String> ingressRecord = ObjectRecord
                .create("whatsapp:ingress:stream", webhookJson)
                .withId(RecordId.of("1779381961001-0"));

        when(whatsappConfigRepository.findTenantIdByPhoneNumberId(phoneNumberId)).thenReturn(Optional.of(tenantId));
        when(resourceManager.canConsume(eq(tenantId), eq(TenantResourceManager.ResourceType.MESSAGES_PER_SECOND), eq(1))).thenReturn(true);
        when(redisTemplate.opsForStream().acknowledge("whatsapp-workers", ingressRecord)).thenReturn(1L);

        webhookWorker.onMessage(ingressRecord);

        // Verify Orchestrator was called
        verify(workflowOrchestrator).startWorkflow(eq(waMessageId), eq(waId), eq(tenantId), eq(webhookJson));
        verify(redisTemplate.opsForStream()).acknowledge("whatsapp-workers", ingressRecord);

        // Stage 2: AI Worker Stage
        ProcessingContext context = ProcessingContext.builder()
                .messageId(waMessageId)
                .waId(waId)
                .tenantId(tenantId)
                .currentStage(ProcessingContext.WorkflowStage.AI_PROCESSING)
                .build();
        context.getMetadata().put("text", "What are your business hours?");

        String aiRecordValue = objectMapper.writeValueAsString(context);
        ObjectRecord<String, String> aiRecord = ObjectRecord
                .create("workflow:ai", aiRecordValue)
                .withId(RecordId.of("1779381961002-0"));

        when(resourceManager.canConsume(eq(tenantId), eq(TenantResourceManager.ResourceType.AI_TOKENS), eq(1))).thenReturn(true);
        when(whatsappConfigRepository.findByTenantId(tenantId)).thenReturn(Optional.of(config));
        when(contactRepository.findByWaIdAndTenant_Id(waId, tenantId)).thenReturn(Optional.of(contact));
        when(messageRepository.findByContactAndDirection(any(), any(), any()))
                .thenReturn(Collections.emptyList());

        GuardrailResult guardrailResult = GuardrailResult.builder()
                .decision(Decision.CALL_AI)
                .reason("intent_recognized")
                .build();
        com.chatcrmlite.backend.dto.memory.ConversationContext memCtx = com.chatcrmlite.backend.dto.memory.ConversationContext.builder().latestQuery("What are your business hours?").build();
        when(conversationMemoryService.getWhatsAppContext(any(), anyString())).thenReturn(memCtx);
        when(guardrailService.evaluate(any(), any(), anyBoolean(), any(), any()))
                .thenReturn(guardrailResult);
        when(ragRetrievalService.getAiResponse(any(com.chatcrmlite.backend.dto.memory.ConversationContext.class), any()))
                .thenReturn("We are open Monday to Friday, 9am to 6pm.");

        when(redisTemplate.opsForStream().acknowledge("whatsapp-workers", aiRecord)).thenReturn(1L);

        aiWorker.onMessage(aiRecord);

        // Verify AI stage completed and scheduled next stage
        ArgumentCaptor<ProcessingContext> aiContextCaptor = ArgumentCaptor.forClass(ProcessingContext.class);
        verify(workflowOrchestrator).completeStage(aiContextCaptor.capture(), eq(ProcessingContext.WorkflowStage.FLOW_EXECUTION));
        ProcessingContext aiCompletedContext = aiContextCaptor.getValue();
        assertEquals("AI", aiCompletedContext.getMetadata().get("responseType"));
        assertEquals("We are open Monday to Friday, 9am to 6pm.", aiCompletedContext.getMetadata().get("pendingResponse"));
        verify(redisTemplate.opsForStream()).acknowledge("whatsapp-workers", aiRecord);

        // Stage 3: Delivery Worker Stage
        aiCompletedContext.setCurrentStage(ProcessingContext.WorkflowStage.DELIVERY);
        String deliveryRecordValue = objectMapper.writeValueAsString(aiCompletedContext);
        ObjectRecord<String, String> deliveryRecord = ObjectRecord
                .create("workflow:delivery", deliveryRecordValue)
                .withId(RecordId.of("1779381961003-0"));

        when(redisTemplate.opsForStream().acknowledge("whatsapp-workers", deliveryRecord)).thenReturn(1L);

        deliveryWorker.onMessage(deliveryRecord);

        // Verify WhatsApp Message delivered to user
        verify(messageService).sendInteractiveAiResponse(eq(contact), eq("We are open Monday to Friday, 9am to 6pm."), eq(config), eq(owner));
        verify(workflowOrchestrator).completeStage(any(ProcessingContext.class), eq(ProcessingContext.WorkflowStage.COMPLETED));
        verify(redisTemplate.opsForStream()).acknowledge("whatsapp-workers", deliveryRecord);
    }

    @Test
    @DisplayName("AI Worker safely handles config with null user by falling back to UserRepository")
    void aiWorker_NullConfigUser_FallsBackToUserRepository() throws Exception {
        Tenant emptyTenant = Tenant.builder().id(tenantId).businessName("Acme Corp").users(Collections.emptySet()).build();
        WhatsAppConfig configNoUser = WhatsAppConfig.builder()
                .id(UUID.randomUUID())
                .tenant(emptyTenant)
                .user(null) // null owner on config
                .phoneNumberId(phoneNumberId)
                .accessToken("EAAX...")
                .build();

        ProcessingContext context = ProcessingContext.builder()
                .messageId(waMessageId)
                .waId(waId)
                .tenantId(tenantId)
                .currentStage(ProcessingContext.WorkflowStage.AI_PROCESSING)
                .build();
        context.getMetadata().put("text", "Hello");

        String aiRecordValue = objectMapper.writeValueAsString(context);
        ObjectRecord<String, String> aiRecord = ObjectRecord
                .create("workflow:ai", aiRecordValue)
                .withId(RecordId.of("1779381961004-0"));

        when(resourceManager.canConsume(eq(tenantId), eq(TenantResourceManager.ResourceType.AI_TOKENS), eq(1))).thenReturn(true);
        when(whatsappConfigRepository.findByTenantId(tenantId)).thenReturn(Optional.of(configNoUser));
        when(userRepository.findByTenantIdAndRole(tenantId, User.Role.OWNER)).thenReturn(List.of(owner));
        when(contactRepository.findByWaIdAndTenant_Id(waId, tenantId)).thenReturn(Optional.of(contact));
        when(messageRepository.findByContactAndDirection(any(), any(), any()))
                .thenReturn(Collections.emptyList());

        GuardrailResult guardrailResult = GuardrailResult.builder()
                .decision(Decision.GREETING)
                .build();
        when(guardrailService.evaluate(any(), any(), anyBoolean(), any(), any()))
                .thenReturn(guardrailResult);

        when(redisTemplate.opsForStream().acknowledge("whatsapp-workers", aiRecord)).thenReturn(1L);

        aiWorker.onMessage(aiRecord);

        ArgumentCaptor<ProcessingContext> aiContextCaptor = ArgumentCaptor.forClass(ProcessingContext.class);
        verify(workflowOrchestrator).completeStage(aiContextCaptor.capture(), eq(ProcessingContext.WorkflowStage.FLOW_EXECUTION));
        assertEquals("GREETING", aiContextCaptor.getValue().getMetadata().get("responseType"));
        verify(redisTemplate.opsForStream()).acknowledge("whatsapp-workers", aiRecord);
    }
}
