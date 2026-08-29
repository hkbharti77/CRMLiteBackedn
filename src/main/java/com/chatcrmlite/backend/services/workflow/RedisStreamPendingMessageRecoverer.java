package com.chatcrmlite.backend.services.workflow;

import com.chatcrmlite.backend.services.DeadLetterHandler;
import com.chatcrmlite.backend.services.WebhookWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Profile;

/**
 * Recovers orphaned messages left in the Redis Stream Pending Entries List (PEL)
 * due to unexpected worker crashes or node failures.
 *
 * Runs every 60 seconds and checks for messages pending > 2 minutes.
 */
@Slf4j
@Service
@Profile("!test")
@RequiredArgsConstructor
public class RedisStreamPendingMessageRecoverer {

    private final StringRedisTemplate redisTemplate;
    private final WebhookWorker webhookWorker;
    private final AIWorker aiWorker;
    private final FlowWorker flowWorker;
    private final DeliveryWorker deliveryWorker;
    private final DeadLetterHandler dlqHandler;

    @Value("${whatsapp.async.stream.ingress}")
    private String ingressStream;

    @Value("${workflow.stream.ai:workflow:ai}")
    private String aiStream;

    @Value("${workflow.stream.flow:workflow:flow}")
    private String flowStream;

    @Value("${workflow.stream.delivery:workflow:delivery}")
    private String deliveryStream;

    @Value("${whatsapp.async.group}")
    private String groupName;

    private static final Duration MIN_IDLE_TIME = Duration.ofMinutes(2);
    private static final int MAX_PENDING_FETCH = 50;
    private static final long MAX_DELIVERY_COUNT = 5;

    @Scheduled(fixedDelay = 60000, initialDelay = 30000)
    public void recoverPendingMessages() {
        recoverStream(ingressStream, webhookWorker);
        recoverStream(aiStream, aiWorker);
        recoverStream(flowStream, flowWorker);
        recoverStream(deliveryStream, deliveryWorker);
    }

    public int recoverStream(String streamKey, StreamListener<String, ObjectRecord<String, String>> listener) {
        int recoveredCount = 0;
        try {
            if (streamKey == null || Boolean.FALSE.equals(redisTemplate.hasKey(streamKey))) {
                return 0;
            }

            PendingMessages pendingMessages = redisTemplate.opsForStream()
                    .pending(streamKey, groupName, Range.unbounded(), MAX_PENDING_FETCH);

            if (pendingMessages == null || pendingMessages.isEmpty()) {
                return 0;
            }

            for (PendingMessage pending : pendingMessages) {
                if (pending.getElapsedTimeSinceLastDelivery().compareTo(MIN_IDLE_TIME) < 0) {
                    // Message was delivered recently (< 2 minutes ago), worker is likely still active. Skip.
                    continue;
                }

                String recordIdStr = pending.getIdAsString();
                log.warn("🔄 [PEL-Recovery] Reclaiming orphaned message id={} on stream={} (idle={}s, retries={})",
                        recordIdStr, streamKey, pending.getElapsedTimeSinceLastDelivery().toSeconds(), pending.getTotalDeliveryCount());

                List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                        .range(streamKey, Range.just(recordIdStr));

                if (records == null || records.isEmpty()) {
                    // Record no longer exists in stream, acknowledge to clear from PEL
                    redisTemplate.opsForStream().acknowledge(groupName, streamKey, recordIdStr);
                    continue;
                }

                MapRecord<String, Object, Object> mapRecord = records.get(0);
                Map<Object, Object> mapValue = mapRecord.getValue();

                // Extract payload string
                String payload = null;
                if (mapValue.containsKey("payload")) {
                    payload = String.valueOf(mapValue.get("payload"));
                } else if (!mapValue.isEmpty()) {
                    // Fallback to first entry value
                    payload = String.valueOf(mapValue.values().iterator().next());
                }

                if (payload == null || payload.isBlank() || "true".equals(payload) || payload.contains("_init")) {
                    redisTemplate.opsForStream().acknowledge(groupName, streamKey, recordIdStr);
                    continue;
                }

                ObjectRecord<String, String> objectRecord = StreamRecords.newRecord()
                        .in(streamKey)
                        .withId(mapRecord.getId())
                        .ofObject(payload);

                if (pending.getTotalDeliveryCount() >= MAX_DELIVERY_COUNT) {
                    log.error("❌ [PEL-Recovery] Message {} exceeded max delivery count ({}). Moving directly to DLQ.",
                            recordIdStr, pending.getTotalDeliveryCount());
                    dlqHandler.moveToDlq(objectRecord, new RuntimeException("PEL max delivery count exceeded"));
                    redisTemplate.opsForStream().acknowledge(groupName, streamKey, recordIdStr);
                } else {
                    // Replay message through consumer listener
                    listener.onMessage(objectRecord);
                }
                recoveredCount++;
            }
        } catch (Exception e) {
            log.warn("⚠️ [PEL-Recovery] Error during pending message recovery on {}: {}", streamKey, e.getMessage());
        }
        return recoveredCount;
    }
}
