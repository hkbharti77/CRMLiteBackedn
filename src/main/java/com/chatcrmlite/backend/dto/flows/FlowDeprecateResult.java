package com.chatcrmlite.backend.dto.flows;

/**
 * Result of a Meta Flow deprecate API call.
 *
 * @param success      true when deprecation succeeded or Meta confirms already deprecated
 * @param httpStatus   HTTP status code; 0 = timeout/network
 * @param errorCode    Meta error code on failure; null on success
 * @param errorMessage Meta error message on failure; null on success
 * @param rawResponse  raw response body
 */
public record FlowDeprecateResult(
        boolean success,
        int httpStatus,
        String errorCode,
        String errorMessage,
        String rawResponse
) {
    public boolean isRetryable() {
        return httpStatus == 408 || httpStatus == 429 || httpStatus >= 500 || httpStatus == 0;
    }

    public static FlowDeprecateResult success(String raw) {
        return new FlowDeprecateResult(true, 200, null, null, raw);
    }

    public static FlowDeprecateResult apiError(int status, String code, String msg, String raw) {
        return new FlowDeprecateResult(false, status, code, msg, raw);
    }

    public static FlowDeprecateResult networkError(String msg) {
        return new FlowDeprecateResult(false, 0, "NETWORK_ERROR", msg, null);
    }
}
