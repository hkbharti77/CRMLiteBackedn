package com.chatcrmlite.backend.dto.flows;

/**
 * Result of a Meta Flow publish API call.
 *
 * @param success      true when publish succeeded or was already published
 * @param httpStatus   HTTP status code; 0 = timeout/network
 * @param errorCode    Meta error code on failure; null on success
 * @param errorMessage Meta error message on failure; null on success
 * @param rawResponse  raw response body
 */
public record FlowPublishResult(
        boolean success,
        int httpStatus,
        String errorCode,
        String errorMessage,
        String rawResponse
) {
    public boolean isRetryable() {
        return httpStatus == 408 || httpStatus == 429 || httpStatus >= 500 || httpStatus == 0;
    }

    public boolean isTerminalError() {
        return !success && !isRetryable();
    }

    public static FlowPublishResult success(String raw) {
        return new FlowPublishResult(true, 200, null, null, raw);
    }

    public static FlowPublishResult apiError(int status, String code, String msg, String raw) {
        return new FlowPublishResult(false, status, code, msg, raw);
    }

    public static FlowPublishResult networkError(String msg) {
        return new FlowPublishResult(false, 0, "NETWORK_ERROR", msg, null);
    }
}
