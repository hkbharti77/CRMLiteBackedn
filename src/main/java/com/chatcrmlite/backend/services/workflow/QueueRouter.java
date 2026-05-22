package com.chatcrmlite.backend.services.workflow;

import com.chatcrmlite.backend.services.tenant.TenantTierService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;

/**
 * Routes processing contexts to the appropriate specialized streams.
 */
@Service
@RequiredArgsConstructor
public class QueueRouter {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(QueueRouter.class);

    private final StringRedisTemplate redisTemplate;
    private final TenantTierService tierService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public void routeToAi(ProcessingContext context) {
        // Removed tier-based routing - all messages go to single stream
        enqueue("workflow:ai", context);
    }

    public void routeToFlow(ProcessingContext context) {
        // Removed tier-based routing - all messages go to single stream
        enqueue("workflow:flow", context);
    }

    public void routeToDelivery(ProcessingContext context) {
        // Removed tier-based routing - all messages go to single stream
        enqueue("workflow:delivery", context);
    }

    private void enqueue(String streamName, ProcessingContext context) {
        log.debug("[Workflow] Routing {} to stream {}", context.getMessageId(), streamName);
        redisTemplate.opsForStream().add(streamName, 
            Collections.singletonMap("payload", serialize(context)));
    }

    private String serialize(ProcessingContext context) {
        try {
            return objectMapper.writeValueAsString(context);
        } catch (Exception e) {
            log.error("Failed to serialize ProcessingContext", e);
            throw new RuntimeException(e);
        }
    }
}
