package com.chatcrmlite.backend.services.websocket;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.io.Serializable;
import java.util.UUID;

/**
 * Represents a WebSocket event to be distributed across nodes.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebSocketEvent implements Serializable {
    private String destination;
    private Object payload;
    private UUID tenantId;
    private long timestamp;
    private String sourceNode; // Useful for debugging/tracing
}
