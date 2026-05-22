package com.chatcrmlite.backend.services.websocket;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Monitors WebSocket connections and message throughput.
 */
@Service
public class WebSocketMetricsService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WebSocketMetricsService.class);

    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private final Counter messagesPublished;
    private final Counter messagesReceived;

    public WebSocketMetricsService(MeterRegistry registry) {
        Gauge.builder("websocket.connections.active", activeConnections, AtomicInteger::get)
                .description("Number of active WebSocket sessions on this node")
                .register(registry);

        this.messagesPublished = registry.counter("websocket.messages.published", "type", "distributed");
        this.messagesReceived = registry.counter("websocket.messages.received", "type", "distributed");
    }

    @EventListener
    public void handleConnect(SessionConnectEvent event) {
        activeConnections.incrementAndGet();
        log.debug("[WS-Metrics] New connection. Active: {}", activeConnections.get());
    }

    @EventListener
    public void handleDisconnect(SessionDisconnectEvent event) {
        activeConnections.decrementAndGet();
        log.debug("[WS-Metrics] Connection closed. Active: {}", activeConnections.get());
    }

    public void recordPublish() {
        messagesPublished.increment();
    }

    public void recordReceive() {
        messagesReceived.increment();
    }

    public int getActiveConnections() {
        return activeConnections.get();
    }
}
