package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import com.chatcrmlite.backend.services.tenant.TenantResourceManager;
import com.chatcrmlite.backend.services.workflow.WorkflowOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.stream.ObjectRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
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

    private WebhookWorker worker;

    @BeforeEach
    void setUp() {
        worker = new WebhookWorker(
                workflowOrchestrator,
                whatsappConfigRepository,
                new ObjectMapper(),
                redisTemplate,
                deadLetterHandler,
                resourceManager
        );
        ReflectionTestUtils.setField(worker, "redisStateService", redisStateService);
        ReflectionTestUtils.setField(worker, "streamName", "whatsapp:ingress:stream");
        ReflectionTestUtils.setField(worker, "groupName", "whatsapp-workers");
        ReflectionTestUtils.setField(worker, "maxRetries", 3);
    }

    @Test
    void statusOnlyWebhookIsAcknowledgedWithoutStartingMessageWorkflow() {
        ObjectRecord<String, String> record = ObjectRecord
                .create("whatsapp:ingress:stream", statusPayload())
                .withId(RecordId.of("1779381961261-0"));
        when(redisTemplate.opsForStream().acknowledge("whatsapp-workers", record)).thenReturn(1L);

        worker.onMessage(record);

        verify(workflowOrchestrator, never()).startWorkflow(anyString(), anyString(), any(), anyString());
        verify(whatsappConfigRepository, never()).findOwnerIdByPhoneNumberId(anyString());
        verify(redisTemplate.opsForStream()).acknowledge(eq("whatsapp-workers"), eq(record));
        verify(redisStateService).delete("worker:retry:1779381961261-0");
    }

    private String statusPayload() {
        return """
                {
                  "entry": [
                    {
                      "changes": [
                        {
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
}
