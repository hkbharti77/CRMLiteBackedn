package com.chatcrmlite.backend.exceptions;

import java.util.UUID;

/**
 * Thrown when the publish worker detects that another revision of the same Flow
 * is currently in the PUBLISHING state, preventing concurrent publish operations.
 *
 * <p>The losing job should reschedule with exponential backoff rather than failing permanently.
 */
public class ConcurrentPublishException extends RuntimeException {

    private final UUID flowId;

    public ConcurrentPublishException(UUID flowId, String message) {
        super(message);
        this.flowId = flowId;
    }

    public UUID getFlowId() {
        return flowId;
    }
}
