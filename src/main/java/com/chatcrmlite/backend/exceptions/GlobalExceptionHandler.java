package com.chatcrmlite.backend.exceptions;

import com.chatcrmlite.backend.controllers.PublicFlowController;
import com.chatcrmlite.backend.controllers.PublicSupportController;
import com.chatcrmlite.backend.services.TicketService;
import com.chatcrmlite.backend.services.tenant.QuotaEnforcerService;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import jakarta.validation.ConstraintViolationException;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

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

    // ── Entitlement & Permissions ─────────────────────────────────────────────
    @ExceptionHandler(com.chatcrmlite.backend.security.EntitlementDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleEntitlementDenied(
            com.chatcrmlite.backend.security.EntitlementDeniedException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", HttpStatus.FORBIDDEN.value());
        body.put("error", "Forbidden");
        body.put("code", ex.getCode());
        body.put("feature", ex.getFeature());
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

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

    @ExceptionHandler(DuplicateContactException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicateContact(
            DuplicateContactException ex) {
        return errorResponse(HttpStatus.CONFLICT, ex.getMessage(), null);
    }

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrityViolation(
            org.springframework.dao.DataIntegrityViolationException ex) {
        if (ex.getMessage() != null && ex.getMessage().contains("uk_contact_waid_owner")) {
            return errorResponse(HttpStatus.CONFLICT, "A contact with this WhatsApp number already exists.", null);
        }
        return errorResponse(HttpStatus.BAD_REQUEST, "Database constraint violation.", null);
    }

    @ExceptionHandler({
            org.springframework.orm.ObjectOptimisticLockingFailureException.class,
            org.hibernate.StaleObjectStateException.class
    })
    public ResponseEntity<Map<String, Object>> handleOptimisticLockingFailure(Exception ex) {
        log.warn("[OptimisticLock] Resource was modified or deleted concurrently: {}", ex.getMessage());
        return errorResponse(HttpStatus.CONFLICT, "The resource was modified or deleted by another request.", null);
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

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleHttpMediaTypeNotSupported(
            org.springframework.web.HttpMediaTypeNotSupportedException ex) {
        log.warn("[HttpMediaTypeNotSupportedException] Content type not supported: {}", ex.getMessage());
        return errorResponse(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "Content-Type '" + ex.getContentType() + "' is not supported. Supported media types: " + ex.getSupportedMediaTypes(), null);
    }

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

    // ── Network & Client Disconnects ─────────────────────────────────────────

    /**
     * Client closed/aborted the connection (e.g. browser refresh, tab closed, network drop).
     * Normal operational event — do not log as 500 error or attempt to write response to aborted socket.
     */
    @ExceptionHandler(org.apache.catalina.connector.ClientAbortException.class)
    public void handleClientAbortException(org.apache.catalina.connector.ClientAbortException ex) {
        log.debug("[ClientAbort] Client disconnected / aborted connection: {}", ex.getMessage());
    }

    @ExceptionHandler(org.springframework.web.context.request.async.AsyncRequestTimeoutException.class)
    public ResponseEntity<Void> handleAsyncRequestTimeoutException(
            org.springframework.web.context.request.async.AsyncRequestTimeoutException ex) {
        log.debug("[AsyncTimeout] Async request timed out: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }

    // ── HTTP Routing & Static Resources (404 / 405) ───────────────────────────

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResourceFound(
            org.springframework.web.servlet.resource.NoResourceFoundException ex) {
        log.debug("[NoResourceFound] Resource not found: {}", ex.getResourcePath());
        return errorResponse(HttpStatus.NOT_FOUND, "Resource not found: " + ex.getResourcePath(), null);
    }

    @ExceptionHandler(org.springframework.web.servlet.NoHandlerFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoHandlerFound(
            org.springframework.web.servlet.NoHandlerFoundException ex) {
        log.debug("[NoHandlerFound] No handler found for {} {}", ex.getHttpMethod(), ex.getRequestURL());
        return errorResponse(HttpStatus.NOT_FOUND, "No endpoint found for " + ex.getHttpMethod() + " " + ex.getRequestURL(), null);
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(
            org.springframework.web.HttpRequestMethodNotSupportedException ex) {
        log.debug("[MethodNotSupported] Method {} not supported for URL", ex.getMethod());
        return errorResponse(HttpStatus.METHOD_NOT_ALLOWED, "HTTP method '" + ex.getMethod() + "' not supported for this endpoint.", null);
    }

    // ── Catch-all ─────────────────────────────────────────────────────────────

    /**
     * SECURITY: Do NOT return ex.getMessage() — may contain SQL, file paths, class names.
     * Log with error ID for support correlation.
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntimeException(RuntimeException ex) {
        if (isClientAbort(ex)) {
            log.debug("[ClientAbort] Client aborted connection during runtime execution: {}", ex.getMessage());
            return null;
        }
        String errorId = getErrorId();
        log.error("[Error={}] Unhandled RuntimeException", errorId, ex);
        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                "An internal error occurred. Reference ID: " + errorId, errorId);
    }

    @ExceptionHandler(Throwable.class)
    public ResponseEntity<Map<String, Object>> handleGeneralException(Throwable ex) {
        if (isClientAbort(ex)) {
            log.debug("[ClientAbort] Client aborted connection: {}", ex.getMessage());
            return null;
        }
        String errorId = getErrorId();
        log.error("[Error={}] Unhandled Throwable", errorId, ex);
        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Reference ID: " + errorId, errorId);
    }

    @ExceptionHandler(HttpMessageNotWritableException.class)
    public ResponseEntity<Map<String, Object>> handleHttpMessageNotWritable(HttpMessageNotWritableException ex) {
        log.warn("[HttpMessageNotWritableException] Failed to write response message: {}", ex.getMessage());
        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Error rendering response.", null);
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<Map<String, Object>> handleHttpMediaTypeNotAcceptable(HttpMediaTypeNotAcceptableException ex) {
        log.warn("[HttpMediaTypeNotAcceptableException] Not acceptable media type requested: {}", ex.getMessage());
        return errorResponse(HttpStatus.NOT_ACCEPTABLE, "Requested media type is not supported.", null);
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    private boolean isClientAbort(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            String name = current.getClass().getName();
            String msg = current.getMessage();
            if (name.contains("ClientAbortException")
                    || (msg != null && (msg.contains("Broken pipe") || msg.contains("connection was aborted")))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private ResponseEntity<Map<String, Object>> errorResponse(HttpStatus status, String message, String errorId) {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            HttpServletResponse response = attrs.getResponse();
            if (response != null && !response.isCommitted()) {
                response.setContentType(org.springframework.http.MediaType.APPLICATION_JSON_VALUE);
            }
        }
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
        return ResponseEntity.status(status)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(body);
    }

    /** Use traceId from MDC (OTEL) if available, otherwise generate a UUID. */
    private String getErrorId() {
        String traceId = MDC.get("traceId");
        return (traceId != null && !traceId.isBlank()) ? traceId : UUID.randomUUID().toString();
    }
}
