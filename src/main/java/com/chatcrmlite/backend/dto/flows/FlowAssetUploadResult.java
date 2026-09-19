package com.chatcrmlite.backend.dto.flows;

import java.util.List;

/**
 * Result of a Meta Flow asset upload attempt.
 *
 * <p>Because Spring {@code RestTemplate} throws {@code RestClientResponseException} on non-2xx
 * responses before normal parsing runs, all Meta API errors are caught and wrapped here rather
 * than letting HTTP status codes be lost. Use the factory methods to construct instances.
 *
 * <pre>
 * Caller pattern:
 *   FlowAssetUploadResult result = metaFlowClient.uploadFlowAssets(...);
 *   if (result.hasValidationErrors()) { ... }  // schema errors in the flow JSON
 *   if (result.isApiError())          { ... }  // Meta API call failed for other reason
 *   if (result.isRetryable())         { ... }  // 5xx / timeout / 429 / 408
 * </pre>
 *
 * @param success            true when Meta returned HTTP 2xx and success=true
 * @param httpStatus         HTTP status code; 0 = timeout / network error
 * @param validationErrors   structured schema errors; empty when none
 * @param errorCode          Meta error code when isApiError(); null otherwise
 * @param errorMessage       human-readable error when isApiError(); null otherwise
 * @param rawResponse        raw response body for logging/debugging
 */
public record FlowAssetUploadResult(
        boolean success,
        int httpStatus,
        List<FlowValidationError> validationErrors,
        String errorCode,
        String errorMessage,
        String rawResponse
) {
    /** True when Meta returned structured schema errors for the uploaded flow.json. */
    public boolean hasValidationErrors() {
        return validationErrors != null && !validationErrors.isEmpty();
    }

    /** True when the Meta API call itself failed (non-2xx or success=false) with no schema errors. */
    public boolean isApiError() {
        return !success && !hasValidationErrors();
    }

    /**
     * True when a retry is appropriate.
     * 408 = request timeout, 429 = rate limit, 5xx = server error, 0 = network/timeout.
     */
    public boolean isRetryable() {
        return httpStatus == 408 || httpStatus == 429 || httpStatus >= 500 || httpStatus == 0;
    }

    // ── Factory methods ───────────────────────────────────────────────────────

    /** 2xx response with optional validation errors (success=true from Meta). */
    public static FlowAssetUploadResult success(List<FlowValidationError> errors, String raw) {
        return new FlowAssetUploadResult(true, 200, errors == null ? List.of() : errors,
                null, null, raw);
    }

    /** Non-2xx response — HTTP status, Meta error code, and message all captured. */
    public static FlowAssetUploadResult apiError(int status, String code, String msg, String raw) {
        return new FlowAssetUploadResult(false, status, List.of(), code, msg, raw);
    }

    /** Timeout or connection error — httpStatus is 0. */
    public static FlowAssetUploadResult networkError(String msg) {
        return new FlowAssetUploadResult(false, 0, List.of(), "NETWORK_ERROR", msg, null);
    }
}
