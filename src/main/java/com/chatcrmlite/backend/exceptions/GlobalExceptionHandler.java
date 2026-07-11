package com.chatcrmlite.backend.exceptions;

import com.chatcrmlite.backend.controllers.PublicFlowController;
import com.chatcrmlite.backend.controllers.PublicSupportController;
import com.chatcrmlite.backend.services.TicketService;
import com.chatcrmlite.backend.services.tenant.QuotaEnforcerService;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Centralized exception handling with:
 *   - Structured error responses (consistent JSON shape)
 *   - Error IDs (traceId fallback) for support correlation
 *   - Security-safe messages (no internal stack info leaked)
 *   - Resilience4j circuit breaker + rate limit handling
 */
@ControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ── Public flow widget ────────────────────────────────────────────────────

    @ExceptionHandler(PublicFlowController.BusinessNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleBusinessNotFound(
            PublicFlowController.BusinessNotFoundException ex) {
        return errorResponse(HttpStatus.NOT_FOUND, "Business not found", null);
    }

    @ExceptionHandler(PublicSupportController.BusinessNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleSupportBusinessNotFound(
            PublicSupportController.BusinessNotFoundException ex) {
        return errorResponse(HttpStatus.NOT_FOUND, "Business not found", null);
    }

    @ExceptionHandler(TicketService.DuplicateTicketException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicateTicket(
            TicketService.DuplicateTicketException ex) {
        return errorResponse(HttpStatus.CONFLICT, ex.getMessage(), null);
    }

    @ExceptionHandler(PublicFlowController.InvalidSubmissionException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidSubmission(
            PublicFlowController.InvalidSubmissionException ex) {
        return errorResponse(HttpStatus.BAD_REQUEST, ex.getMessage(), null);
    }

    // ── Billing & Quotas ──────────────────────────────────────────────────────

    @ExceptionHandler(QuotaEnforcerService.QuotaExceededException.class)
    public ResponseEntity<Map<String, Object>> handleQuotaExceeded(
            QuotaEnforcerService.QuotaExceededException ex) {
        return errorResponse(HttpStatus.PAYMENT_REQUIRED, ex.getMessage(), null);
    }

    @ExceptionHandler(QuotaEnforcerService.SubscriptionExpiredException.class)
    public ResponseEntity<Map<String, Object>> handleSubscriptionExpired(
            QuotaEnforcerService.SubscriptionExpiredException ex) {
        return errorResponse(HttpStatus.PAYMENT_REQUIRED, ex.getMessage(), null);
    }

    // ── Validation ────────────────────────────────────────────────────────────

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationException(
            MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return errorResponse(HttpStatus.BAD_REQUEST, message, null);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(
            ConstraintViolationException ex) {
        return errorResponse(HttpStatus.BAD_REQUEST, "Validation failed: " + ex.getMessage(), null);
    }

    // ── File upload ───────────────────────────────────────────────────────────

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxSizeException(
            org.springframework.web.multipart.MaxUploadSizeExceededException ex) {
        return errorResponse(HttpStatus.BAD_REQUEST, "File size exceeds the maximum allowed limit.", null);
    }

    // ── Resilience4j ─────────────────────────────────────────────────────────

    /**
     * Circuit breaker open — downstream service is unhealthy.
     * Return 503 with Retry-After header.
     */
    @ExceptionHandler(CallNotPermittedException.class)
    public ResponseEntity<Map<String, Object>> handleCircuitBreakerOpen(
            CallNotPermittedException ex) {
        log.warn("[CircuitBreaker] OPEN for: {}", ex.getCausingCircuitBreakerName());
        var response = errorResponse(HttpStatus.SERVICE_UNAVAILABLE,
                "Service temporarily unavailable. Please try again shortly.", null);
        response.getHeaders().add("Retry-After", "30");
        return response;
    }

    /**
     * Resilience4j rate limiter (as opposed to Bucket4j which operates at filter level).
     */
    @ExceptionHandler(RequestNotPermitted.class)
    public ResponseEntity<Map<String, Object>> handleRateLimitExceeded(
            RequestNotPermitted ex) {
        log.warn("[RateLimit] Resilience4j rate limit exceeded: {}", ex.getMessage());
        var response = errorResponse(HttpStatus.TOO_MANY_REQUESTS,
                "Too many requests. Please slow down.", null);
        response.getHeaders().add("Retry-After", "60");
        return response;
    }

    // ── Security ──────────────────────────────────────────────────────────────

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(
            org.springframework.security.access.AccessDeniedException ex) {
        log.warn("[Security] Access denied: {}", ex.getMessage());
        return errorResponse(HttpStatus.FORBIDDEN, "Access denied.", null);
    }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatusException(
            org.springframework.web.server.ResponseStatusException ex) {
        return errorResponse(org.springframework.http.HttpStatus.valueOf(ex.getStatusCode().value()), ex.getReason(), null);
    }

    // ── Catch-all ─────────────────────────────────────────────────────────────

    /**
     * SECURITY: Do NOT return ex.getMessage() — may contain SQL, file paths, class names.
     * Log with error ID for support correlation.
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntimeException(RuntimeException ex) {
        String errorId = getErrorId();
        log.error("[Error={}] Unhandled RuntimeException", errorId, ex);
        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                "An internal error occurred. Reference ID: " + errorId, errorId);
    }

    @ExceptionHandler(Throwable.class)
    public ResponseEntity<Map<String, Object>> handleGeneralException(Throwable ex) {
        String errorId = getErrorId();
        log.error("[Error={}] Unhandled Throwable", errorId, ex);
        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Reference ID: " + errorId, errorId);
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> errorResponse(HttpStatus status, String message, String errorId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        if (errorId != null) {
            body.put("errorId", errorId);
        }
        // Include traceId for log correlation if available
        String traceId = MDC.get("traceId");
        if (traceId != null) {
            body.put("traceId", traceId);
        }
        return new ResponseEntity<>(body, status);
    }

    /** Use traceId from MDC (OTEL) if available, otherwise generate a UUID. */
    private String getErrorId() {
        String traceId = MDC.get("traceId");
        return (traceId != null && !traceId.isBlank()) ? traceId : UUID.randomUUID().toString();
    }
}
