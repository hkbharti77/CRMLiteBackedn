package com.chatcrmlite.backend.services.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Publisher for distributed WebSocket events.
 * Use this instead of SimpMessagingTemplate to ensure all nodes receive the broadcast.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DistributedWebSocketPublisher {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ChannelTopic webSocketTopic;
    private final WebSocketMetricsService metricsService;

    /**
     * Publishes a message to a destination for a specific tenant.
     * The event will be distributed to all backend nodes via Redis Pub/Sub.
     */
    public void publish(UUID tenantId, String destination, Object payload) {
        log.info("📢 [WebSocket-Publisher] Publishing event to {} for tenant {}", destination, tenantId);

        metricsService.recordPublish();

        WebSocketEvent event = WebSocketEvent.builder()
                .tenantId(tenantId)
                .destination(destination)
                .payload(payload)
                .timestamp(System.currentTimeMillis())
                .sourceNode(System.getProperty("server.node.id", "unknown-node"))
                .build();

        // Push to Redis Pub/Sub channel
        redisTemplate.convertAndSend(webSocketTopic.getTopic(), event);
    }

    /**
     * Shorthand for standard tenant message broadcast.
     */
    public void publishMessage(UUID tenantId, Object messageDto) {
        String destination = "/topic/" + tenantId + "/messages";
        publish(tenantId, destination, messageDto);
    }
}
