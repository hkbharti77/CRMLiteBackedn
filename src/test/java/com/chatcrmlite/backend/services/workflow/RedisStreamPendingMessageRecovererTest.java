package com.chatcrmlite.backend.services.workflow;

import com.chatcrmlite.backend.services.DeadLetterHandler;
import com.chatcrmlite.backend.services.WebhookWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class RedisStreamPendingMessageRecovererTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private StreamOperations<String, Object, Object> streamOperations;

    @Mock
    private WebhookWorker webhookWorker;

    @Mock
    private AIWorker aiWorker;

    @Mock
    private FlowWorker flowWorker;

    @Mock
    private DeliveryWorker deliveryWorker;

    @Mock
    private DeadLetterHandler dlqHandler;

    @InjectMocks
    private RedisStreamPendingMessageRecoverer recoverer;

    private final String streamKey = "whatsapp:stream:ingress";
    private final String groupName = "whatsapp-worker-group";

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(recoverer, "groupName", groupName);
        ReflectionTestUtils.setField(recoverer, "ingressStream", streamKey);
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
    }

    @Test
    @DisplayName("Orphaned pending message (> 2 mins idle) is reclaimed and processed by worker")
    void testOrphanedMessage_IsReclaimedAndProcessed() {
        when(redisTemplate.hasKey(streamKey)).thenReturn(true);

        RecordId recordId = RecordId.of("1700000000000-0");
        PendingMessage pending = new PendingMessage(
                recordId,
                Consumer.from(groupName, "dead-worker"),
                Duration.ofMinutes(5), // 5 minutes idle > 2 minutes threshold
                2
        );

        PendingMessages pendingMessages = new PendingMessages(groupName, List.of(pending));
        when(streamOperations.pending(eq(streamKey), eq(groupName), any(Range.class), anyLong()))
                .thenReturn(pendingMessages);

        MapRecord<String, Object, Object> mapRecord = StreamRecords.newRecord()
                .in(streamKey)
                .withId(recordId)
                .ofMap(Map.of("payload", "{\"messageId\":\"msg_recovery_001\"}"));

        when(streamOperations.range(eq(streamKey), any(Range.class)))
                .thenReturn(List.of(mapRecord));

        int count = recoverer.recoverStream(streamKey, webhookWorker);
        assertEquals(1, count);
        verify(webhookWorker).onMessage(any(ObjectRecord.class));
        verify(dlqHandler, never()).moveToDlq(any(), any());
    }

    @Test
    @DisplayName("Recently active message (< 2 mins idle) is not prematurely reclaimed")
    void testRecentlyActiveMessage_IsNotReclaimed() {
        when(redisTemplate.hasKey(streamKey)).thenReturn(true);

        RecordId recordId = RecordId.of("1700000000000-0");
        PendingMessage pending = new PendingMessage(
                recordId,
                Consumer.from(groupName, "active-worker"),
                Duration.ofSeconds(30), // Only 30s idle < 2 minutes threshold
                1
        );

        PendingMessages pendingMessages = new PendingMessages(groupName, List.of(pending));
        when(streamOperations.pending(eq(streamKey), eq(groupName), any(Range.class), anyLong()))
                .thenReturn(pendingMessages);

        int count = recoverer.recoverStream(streamKey, webhookWorker);
        assertEquals(0, count);
        verify(webhookWorker, never()).onMessage(any());
    }

    @Test
    @DisplayName("Orphaned message exceeding max delivery count is routed directly to DLQ and ACKed")
    void testOrphanedMessage_ExceedingMaxDelivery_SentToDlq() {
        when(redisTemplate.hasKey(streamKey)).thenReturn(true);

        RecordId recordId = RecordId.of("1700000000000-0");
        PendingMessage pending = new PendingMessage(
                recordId,
                Consumer.from(groupName, "crashed-worker"),
                Duration.ofMinutes(10),
                6 // 6 retries >= 5 max delivery count
        );

        PendingMessages pendingMessages = new PendingMessages(groupName, List.of(pending));
        when(streamOperations.pending(eq(streamKey), eq(groupName), any(Range.class), anyLong()))
                .thenReturn(pendingMessages);

        MapRecord<String, Object, Object> mapRecord = StreamRecords.newRecord()
                .in(streamKey)
                .withId(recordId)
                .ofMap(Map.of("payload", "{\"messageId\":\"msg_poison_pill\"}"));

        when(streamOperations.range(eq(streamKey), any(Range.class)))
                .thenReturn(List.of(mapRecord));

        int count = recoverer.recoverStream(streamKey, webhookWorker);
        assertEquals(1, count);
        verify(dlqHandler).moveToDlq(any(ObjectRecord.class), any(RuntimeException.class));
        verify(streamOperations).acknowledge(eq(groupName), eq(streamKey), eq(recordId.getValue()));
        verify(webhookWorker, never()).onMessage(any());
    }
}
