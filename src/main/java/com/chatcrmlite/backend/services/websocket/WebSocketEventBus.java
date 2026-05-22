package com.chatcrmlite.backend.services.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.io.Serializable;

/**
 * Listens for events from Redis Pub/Sub and forwards them to local WebSocket subscribers.
 */
@Service
@RequiredArgsConstructor
public class WebSocketEventBus {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WebSocketEventBus.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final WebSocketMetricsService metricsService;

    /**
     * Called by Redis MessageListenerAdapter when a message is received on the topic.
     */
    public void handleMessage(Serializable message) {
        if (message instanceof WebSocketEvent) {
            WebSocketEvent event = (WebSocketEvent) message;
            log.debug("[WebSocket-Bus] Received distributed event for destination: {}", event.getDestination());
            
            metricsService.recordReceive();
            // FORWARD to local connected clients on this node
            messagingTemplate.convertAndSend(event.getDestination(), event.getPayload());
        } else {
            log.warn("[WebSocket-Bus] Received unknown message type: {}", message.getClass().getName());
        }
    }
}
