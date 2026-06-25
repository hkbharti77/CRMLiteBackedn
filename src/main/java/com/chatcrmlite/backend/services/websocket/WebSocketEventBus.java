package com.chatcrmlite.backend.services.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.io.Serializable;

/**
 * Listens for events from Redis Pub/Sub and forwards them to local WebSocket subscribers.
 *
 * NOTE: Redis serializes with Jackson default typing enabled (NON_FINAL), which wraps
 * the payload as a 2-element JSON array: ["com.example.ClassName", {...}].
 * This handler detects both plain-object and type-wrapped-array formats.
 */
@Service
@RequiredArgsConstructor
public class WebSocketEventBus {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WebSocketEventBus.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final WebSocketMetricsService metricsService;
    private final ObjectMapper objectMapper;

    /**
     * Called by Redis MessageListenerAdapter when a message is received on the topic.
     */
    public void handleMessage(Serializable message) {
        if (message instanceof WebSocketEvent) {
            forwardEvent((WebSocketEvent) message);
        } else if (message instanceof String) {
            deserializeAndForward((String) message);
        } else if (message instanceof byte[]) {
            deserializeAndForward(new String((byte[]) message));
        } else {
            log.warn("[WebSocket-Bus] Received unknown message type: {}",
                    message != null ? message.getClass().getName() : "null");
        }
    }

    /**
     * Deserializes a JSON string to WebSocketEvent.
     * Handles two possible formats produced by the Redis Jackson serializer:
     * <ul>
     *   <li>Plain object: {@code {"destination":"...","payload":...}}</li>
     *   <li>Type-wrapped array (default typing ON): {@code ["com.example.WebSocketEvent",{...}]}</li>
     * </ul>
     */
    private void deserializeAndForward(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);

            WebSocketEvent event;
            if (root.isArray() && root.size() == 2 && root.get(0).isTextual()) {
                // Jackson default-typing wraps as ["ClassName", {fields}]
                log.debug("[WebSocket-Bus] Detected type-wrapped array format, extracting object node");
                event = objectMapper.treeToValue(root.get(1), WebSocketEvent.class);
            } else {
                // Plain JSON object format
                event = objectMapper.treeToValue(root, WebSocketEvent.class);
            }

            forwardEvent(event);
        } catch (Exception e) {
            log.error("[WebSocket-Bus] Failed to deserialize WebSocketEvent. Raw message: [{}]", json, e);
        }
    }

    private void forwardEvent(WebSocketEvent event) {
        log.debug("[WebSocket-Bus] Received distributed event for destination: {}", event.getDestination());
        metricsService.recordReceive();
        // FORWARD to local connected clients on this node
        messagingTemplate.convertAndSend(event.getDestination(), event.getPayload());
    }
}
