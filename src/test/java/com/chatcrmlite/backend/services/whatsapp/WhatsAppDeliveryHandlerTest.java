package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import com.chatcrmlite.backend.services.DeadLetterHandler;
import com.chatcrmlite.backend.services.workflow.DeliveryWorker;
import com.chatcrmlite.backend.services.workflow.ProcessingContext;
import com.chatcrmlite.backend.services.workflow.WorkflowOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.stream.ObjectRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class WhatsAppDeliveryHandlerTest {

    @Mock
    private WhatsAppConfigRepository whatsappConfigRepository;

    @Mock
    private ContactRepository contactRepository;

    @Mock
    private WhatsAppMessageService messageService;

    @Mock
    private WhatsAppMenuService menuService;

    @Mock
    private WorkflowOrchestrator orchestrator;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private StreamOperations<String, Object, Object> streamOperations;

    @Mock
    private DeadLetterHandler dlqHandler;

    @InjectMocks
    private WhatsAppDeliveryHandler deliveryHandler;

    private DeliveryWorker deliveryWorker;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID tenantId = UUID.randomUUID();
    private final String waId = "919876543210";

    @BeforeEach
    void setUp() {
        deliveryWorker = new DeliveryWorker(
                orchestrator,
                null,
                whatsappConfigRepository,
                redisTemplate,
                deliveryHandler,
                objectMapper
        );
        ReflectionTestUtils.setField(deliveryWorker, "dlqHandler", dlqHandler);
        ReflectionTestUtils.setField(deliveryWorker, "groupName", "whatsapp-worker-group");
    }

    @Test
    @DisplayName("DeliveryHandler propagates Meta API exceptions instead of swallowing them")
    void testDeliverResponse_PropagatesExceptionOnMetaFailure() {
        ProcessingContext context = ProcessingContext.builder()
                .messageId("msg_fail_001")
                .tenantId(tenantId)
                .waId(waId)
                .build();
        context.getMetadata().put("responseType", "AI");
        context.getMetadata().put("pendingResponse", "AI generated text");

        WhatsAppConfig config = new WhatsAppConfig();
        User user = new User();
        config.setUser(user);

        Contact contact = new Contact();
        contact.setWaId(waId);

        when(whatsappConfigRepository.findByTenantId(tenantId)).thenReturn(Optional.of(config));
        when(contactRepository.findByWaIdAndTenant_Id(waId, tenantId)).thenReturn(Optional.of(contact));
        doThrow(new RuntimeException("Meta Graph API 500: Temporary server error"))
                .when(messageService).sendInteractiveAiResponse(any(), anyString(), any(), any());

        RuntimeException ex = assertThrows(RuntimeException.class, () -> deliveryHandler.deliverResponse(context));
        assertTrue(ex.getMessage().contains("Meta Graph API 500"));
    }

    @Test
    @DisplayName("DeliveryWorker catches delivery failure, transitions stage to FAILED, and sends to DLQ")
    void testDeliveryWorker_HandlesFailureAndRoutesToDlq() throws Exception {
        ProcessingContext context = ProcessingContext.builder()
                .messageId("msg_fail_002")
                .tenantId(tenantId)
                .waId(waId)
                .build();
        context.getMetadata().put("responseType", "AI");
        context.getMetadata().put("pendingResponse", "AI generated reply");

        String jsonPayload = objectMapper.writeValueAsString(context);
        ObjectRecord<String, String> record = StreamRecords.newRecord()
                .in("workflow:delivery")
                .withId(RecordId.of("1700000000000-0"))
                .ofObject(jsonPayload);

        when(whatsappConfigRepository.findByTenantId(tenantId))
                .thenThrow(new RuntimeException("Meta API timeout"));
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);

        deliveryWorker.onMessage(record);

        verify(orchestrator).completeStage(any(ProcessingContext.class), eq(ProcessingContext.WorkflowStage.FAILED));
        verify(dlqHandler).moveToDlq(eq(record), any(Exception.class));
        verify(streamOperations).acknowledge(eq("whatsapp-worker-group"), eq(record));
    }
}
