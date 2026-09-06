package com.chatcrmlite.backend.services.workflow;

import com.chatcrmlite.backend.services.tenant.TenantTierService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import java.util.Collections;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Routes processing contexts to the appropriate specialized streams.
 */
@Service
@RequiredArgsConstructor
public class QueueRouter {
    private static final Logger log = LoggerFactory.getLogger(QueueRouter.class);

    private final StringRedisTemplate redisTemplate;
    private final TenantTierService tierService;
    private final ObjectMapper objectMapper;

    @Value("${workflow.stream.ai:workflow:ai}")
    private String aiStream;

    @Value("${workflow.stream.flow:workflow:flow}")
    private String flowStream;

    @Value("${workflow.stream.delivery:workflow:delivery}")
    private String deliveryStream;

    public void routeToAi(ProcessingContext context) {
        enqueue(aiStream, context);
    }

    public void routeToFlow(ProcessingContext context) {
        enqueue(flowStream, context);
    }

    public void routeToDelivery(ProcessingContext context) {
        enqueue(deliveryStream, context);
    }

    private void enqueue(String targetStream, ProcessingContext context) {
        log.info("[WhatsApp-Queue] Routing messageId={} to stream={}", context.getMessageId(), targetStream);
        MapRecord<String, String, String> record = StreamRecords.newRecord()
                .in(targetStream)
                .ofMap(Collections.singletonMap("payload", serialize(context)));
        redisTemplate.opsForStream().add(record);
    }

    private String serialize(ProcessingContext context) {
        try {
            return objectMapper.writeValueAsString(context);
        } catch (Exception e) {
            log.error("Failed to serialize ProcessingContext for messageId={}", context.getMessageId(), e);
            throw new RuntimeException(e);
        }
    }
}
