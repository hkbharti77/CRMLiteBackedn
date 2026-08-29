package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import com.chatcrmlite.backend.services.tenant.TenantResourceManager;
import com.chatcrmlite.backend.services.workflow.WorkflowOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.stream.ObjectRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebhookWorkerTest {

    @Mock
    private WorkflowOrchestrator workflowOrchestrator;

    @Mock
    private WhatsAppConfigRepository whatsappConfigRepository;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private StringRedisTemplate redisTemplate;

    @Mock
    private DeadLetterHandler deadLetterHandler;

    @Mock
    private TenantResourceManager resourceManager;

    @Mock
    private RedisStateService redisStateService;

    @Mock
    private com.chatcrmlite.backend.repositories.WhatsAppTemplateRepository whatsappTemplateRepository;

    @Mock
    private com.chatcrmlite.backend.repositories.TenantRepository tenantRepository;

    private WebhookWorker worker;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        worker = new WebhookWorker(
                workflowOrchestrator,
                whatsappConfigRepository,
                new ObjectMapper(),
                redisTemplate,
                deadLetterHandler,
                resourceManager,
                whatsappTemplateRepository,
                tenantRepository
        );
        ReflectionTestUtils.setField(worker, "redisStateService", redisStateService);
        ReflectionTestUtils.setField(worker, "streamName", "whatsapp:ingress:stream");
        ReflectionTestUtils.setField(worker, "groupName", "whatsapp-workers");
        ReflectionTestUtils.setField(worker, "maxRetries", 3);
    }

    @Test
    @DisplayName("Status-only webhook is acknowledged without starting message workflow")
    void statusOnlyWebhookIsAcknowledgedWithoutStartingMessageWorkflow() {
        ObjectRecord<String, String> record = ObjectRecord
                .create("whatsapp:ingress:stream", statusPayload())
                .withId(RecordId.of("1779381961261-0"));
        when(redisTemplate.opsForStream().acknowledge("whatsapp-workers", record)).thenReturn(1L);

        worker.onMessage(record);

        verify(workflowOrchestrator, never()).startWorkflow(anyString(), anyString(), any(), anyString());
        verify(redisTemplate.opsForStream()).acknowledge(eq("whatsapp-workers"), eq(record));
        verify(redisStateService).delete("worker:retry:1779381961261-0");
    }

    @Test
    @DisplayName("Valid incoming text message is parsed, handed to orchestrator, and acknowledged")
    void validIncomingMessage_StartsWorkflowAndAcknowledges() {
        String msgPayload = incomingMessagePayload("123456", "919876543210", "wamid.msg.001", "Hello Bot");
        ObjectRecord<String, String> record = ObjectRecord
                .create("whatsapp:ingress:stream", msgPayload)
                .withId(RecordId.of("1779381961262-0"));

        when(whatsappConfigRepository.findTenantIdByPhoneNumberId("123456")).thenReturn(Optional.of(tenantId));
        when(resourceManager.canConsume(eq(tenantId), any(), anyInt())).thenReturn(true);
        when(redisTemplate.opsForStream().acknowledge("whatsapp-workers", record)).thenReturn(1L);

        worker.onMessage(record);

        verify(workflowOrchestrator).startWorkflow(eq("wamid.msg.001"), eq("919876543210"), eq(tenantId), eq(msgPayload));
        verify(redisTemplate.opsForStream()).acknowledge(eq("whatsapp-workers"), eq(record));
        verify(redisStateService).delete("worker:retry:1779381961262-0");
    }

    @Test
    @DisplayName("Wrapped payload (with outer {\"payload\": ...}) is safely unwrapped and processed")
    void wrappedPayload_IsSafelyUnwrappedAndProcessed() {
        String innerPayload = incomingMessagePayload("123456", "919876543210", "wamid.msg.002", "Hi again");
        String wrappedJson = "{\"payload\":" + new ObjectMapper().valueToTree(innerPayload).toString() + "}";
        ObjectRecord<String, String> record = ObjectRecord
                .create("whatsapp:ingress:stream", wrappedJson)
                .withId(RecordId.of("1779381961263-0"));

        when(whatsappConfigRepository.findTenantIdByPhoneNumberId("123456")).thenReturn(Optional.of(tenantId));
        when(resourceManager.canConsume(eq(tenantId), any(), anyInt())).thenReturn(true);
        when(redisTemplate.opsForStream().acknowledge("whatsapp-workers", record)).thenReturn(1L);

        worker.onMessage(record);

        verify(workflowOrchestrator).startWorkflow(eq("wamid.msg.002"), eq("919876543210"), eq(tenantId), anyString());
        verify(redisTemplate.opsForStream()).acknowledge(eq("whatsapp-workers"), eq(record));
    }

    @Test
    @DisplayName("Malformed or empty payload is safely acknowledged without crashing")
    void emptyOrMalformedPayload_AcknowledgedSafely() {
        ObjectRecord<String, String> record = ObjectRecord
                .create("whatsapp:ingress:stream", "{\"invalid\":\"json_no_entry\"}")
                .withId(RecordId.of("1779381961264-0"));

        worker.onMessage(record);

        verify(workflowOrchestrator, never()).startWorkflow(anyString(), anyString(), any(), anyString());
        verify(redisTemplate.opsForStream()).acknowledge(eq("whatsapp-workers"), eq(record));
    }

    @Test
    @DisplayName("Unknown phoneNumberId safely acknowledges and drops message without crashing")
    void unknownPhoneNumberId_SafelyAcknowledged() {
        String msgPayload = incomingMessagePayload("unknown-phone-id", "919876543210", "wamid.msg.003", "Test");
        ObjectRecord<String, String> record = ObjectRecord
                .create("whatsapp:ingress:stream", msgPayload)
                .withId(RecordId.of("1779381961265-0"));

        when(whatsappConfigRepository.findTenantIdByPhoneNumberId("unknown-phone-id")).thenReturn(Optional.empty());

        worker.onMessage(record);

        verify(workflowOrchestrator, never()).startWorkflow(anyString(), anyString(), any(), anyString());
        verify(redisTemplate.opsForStream()).acknowledge(eq("whatsapp-workers"), eq(record));
    }

    private String statusPayload() {
        return """
                {
                  "entry": [
                    {
                      "changes": [
                        {
                          "field": "messages",
                          "value": {
                            "metadata": { "phone_number_id": "phone-id" },
                            "statuses": [
                              {
                                "id": "wamid.outgoing",
                                "recipient_id": "919900000000",
                                "status": "read",
                                "timestamp": "1779381961",
                                "conversation": { "id": "conversation-id" }
                              }
                            ]
                          }
                        }
                      ]
                    }
                  ]
                }
                """;
    }

    private String incomingMessagePayload(String phoneNumberId, String from, String messageId, String text) {
        return """
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
                              {
                                "profile": { "name": "John Doe" },
                                "wa_id": "%s"
                              }
                            ],
                            "messages": [
                              {
                                "from": "%s",
                                "id": "%s",
                                "timestamp": "1779381961",
                                "text": { "body": "%s" },
                                "type": "text"
                              }
                            ]
                          }
                        }
                      ]
                    }
                  ]
                }
                """.formatted(phoneNumberId, from, from, messageId, text);
    }
}
