package com.chatcrmlite.backend.exceptions;

/**
 * Thrown when a Meta API response is missing an expected structural field
 * (e.g. the {@code success} flag is absent or not a boolean).
 *
 * <p>This is a programming/API contract error — it is treated as a terminal
 * publish failure and is not retried.
 */
public class MetaFlowResponseException extends RuntimeException {

    public MetaFlowResponseException(String message) {
        super(message);
    }

    public MetaFlowResponseException(String message, Throwable cause) {
        super(message, cause);
    }
}
