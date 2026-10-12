package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.dto.whatsapp.calling.*;
import com.chatcrmlite.backend.services.whatsapp.WhatsAppCallingApiService;
import com.chatcrmlite.backend.utils.TenantResolver;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

/**
 * REST API Controller for WhatsApp Voice Calling Agent operations.
 * Exposes outbound call initiation, call session retrieval, transcripts, and calling analytics.
 * Strictly tenant-isolated via authenticated TenantResolver.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/voice/whatsapp")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class WhatsAppCallController {

    private final WhatsAppCallingApiService callingApiService;
    private final TenantResolver tenantResolver;

    /**
     * Initiates an outbound WhatsApp voice call.
     *
     * @param request call initiation payload
     * @param idempotencyKey optional idempotency key in header
     * @return call session response
     */
    @PostMapping("/calls")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'AGENT', 'SUPER_ADMIN', 'PLATFORM_ADMIN') or @perm.has(authentication, 'MODULE_INBOX')")
    public ResponseEntity<WhatsAppCallResponse> initiateOutboundCall(
            @Valid @RequestBody InitiateWhatsAppCallRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        UUID tenantId = tenantResolver.resolveAuthenticatedTenantId();
        WhatsAppCallResponse response = callingApiService.initiateOutboundCall(tenantId, request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Lists calling sessions for the authenticated tenant with filtering, pagination, and sorting.
     */
    @GetMapping("/calls")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'AGENT', 'SUPER_ADMIN', 'PLATFORM_ADMIN') or @perm.has(authentication, 'MODULE_INBOX')")
    public ResponseEntity<Page<WhatsAppCallSummaryDTO>> listCalls(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sort,
            @RequestParam(defaultValue = "DESC") String direction,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, name = "callDirection") String callDirection,
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) UUID contactId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to
    ) {
        UUID tenantId = tenantResolver.resolveAuthenticatedTenantId();
        Page<WhatsAppCallSummaryDTO> result = callingApiService.listCalls(
                tenantId, page, size, sort, direction, status, callDirection, phone, contactId, from, to
        );
        return ResponseEntity.ok(result);
    }

    /**
     * Retrieves detailed information for a specific call session.
     */
    @GetMapping("/calls/{callId}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'AGENT', 'SUPER_ADMIN', 'PLATFORM_ADMIN') or @perm.has(authentication, 'MODULE_INBOX')")
    public ResponseEntity<WhatsAppCallDetailDTO> getCallDetails(@PathVariable String callId) {
        UUID tenantId = tenantResolver.resolveAuthenticatedTenantId();
        WhatsAppCallDetailDTO details = callingApiService.getCallDetails(tenantId, callId);
        return ResponseEntity.ok(details);
    }

    /**
     * Retrieves conversation turns and transcript for a specific call session.
     */
    @GetMapping("/calls/{callId}/turns")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'AGENT', 'SUPER_ADMIN', 'PLATFORM_ADMIN') or @perm.has(authentication, 'MODULE_INBOX')")
    public ResponseEntity<Page<WhatsAppCallTurnDTO>> getCallTurns(
            @PathVariable String callId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        UUID tenantId = tenantResolver.resolveAuthenticatedTenantId();
        Page<WhatsAppCallTurnDTO> turns = callingApiService.getCallTurns(tenantId, callId, page, size);
        return ResponseEntity.ok(turns);
    }

    /**
     * Terminates an active call session on demand.
     */
    @PostMapping("/calls/{callId}/terminate")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'AGENT', 'SUPER_ADMIN', 'PLATFORM_ADMIN') or @perm.has(authentication, 'MODULE_INBOX')")
    public ResponseEntity<java.util.Map<String, Object>> terminateCall(@PathVariable String callId) {
        UUID tenantId = tenantResolver.resolveAuthenticatedTenantId();
        callingApiService.terminateCall(tenantId, callId);
        return ResponseEntity.ok(java.util.Map.of("success", true, "callId", callId, "message", "Call termination requested"));
    }

    /**
     * Retrieves recording metadata for a specific call session.
     */
    @GetMapping("/calls/{callId}/recording")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'AGENT', 'SUPER_ADMIN', 'PLATFORM_ADMIN') or @perm.has(authentication, 'MODULE_INBOX')")
    public ResponseEntity<WhatsAppCallRecordingDTO> getCallRecording(@PathVariable String callId) {
        UUID tenantId = tenantResolver.resolveAuthenticatedTenantId();
        WhatsAppCallRecordingDTO recording = callingApiService.getCallRecording(tenantId, callId);
        return ResponseEntity.ok(recording);
    }

    /**
     * Streams call recording audio with HTTP 206 Partial Content support for browser seeking.
     * Accessible via authenticated bearer token or time-limited signed query token.
     */
    @GetMapping("/calls/{callId}/recording/stream")
    @PreAuthorize("permitAll()")
    public ResponseEntity<byte[]> streamCallRecording(
            @PathVariable String callId,
            @RequestParam(required = false) String token,
            @RequestHeader(value = "Range", required = false) String rangeHeader
    ) {
        UUID authenticatedTenantId = null;
        try {
            authenticatedTenantId = tenantResolver.resolveAuthenticatedTenantId();
        } catch (Exception ignored) {}

        return callingApiService.streamCallRecording(authenticatedTenantId, callId, token, rangeHeader);
    }

    /**
     * Securely downloads call recording audio file.
     * Accessible via authenticated bearer token or time-limited signed query token.
     */
    @GetMapping("/calls/{callId}/recording/download")
    @PreAuthorize("permitAll()")
    public ResponseEntity<byte[]> downloadCallRecording(
            @PathVariable String callId,
            @RequestParam(required = false) String token
    ) {
        UUID authenticatedTenantId = null;
        try {
            authenticatedTenantId = tenantResolver.resolveAuthenticatedTenantId();
        } catch (Exception ignored) {}

        return callingApiService.downloadCallRecording(authenticatedTenantId, callId, token);
    }

    /**
     * Updates call recording metadata idempotently.
     */
    @PostMapping("/calls/{callId}/recording")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'SUPER_ADMIN', 'PLATFORM_ADMIN')")
    public ResponseEntity<WhatsAppCallRecordingDTO> updateCallRecording(
            @PathVariable String callId,
            @RequestBody java.util.Map<String, Object> payload
    ) {
        UUID tenantId = tenantResolver.resolveAuthenticatedTenantId();
        String publicId = (String) payload.get("publicId");
        String url = (String) payload.get("url");
        String format = (String) payload.get("format");
        Integer duration = payload.get("durationSeconds") instanceof Number ? ((Number) payload.get("durationSeconds")).intValue() : null;
        Long sizeBytes = payload.get("sizeBytes") instanceof Number ? ((Number) payload.get("sizeBytes")).longValue() : null;
        String status = (String) payload.get("status");

        WhatsAppCallRecordingDTO recording = callingApiService.saveOrUpdateCallRecording(
                tenantId, callId, publicId, url, format, duration, sizeBytes, status
        );
        return ResponseEntity.ok(recording);
    }

    /**
     * Uploads a call recording audio file directly for a specific call session.
     */
    @PostMapping(value = "/calls/{callId}/recording/upload", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'SUPER_ADMIN', 'PLATFORM_ADMIN')")
    public ResponseEntity<WhatsAppCallRecordingDTO> uploadCallRecording(
            @PathVariable String callId,
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file
    ) {
        UUID tenantId = tenantResolver.resolveAuthenticatedTenantId();
        WhatsAppCallRecordingDTO recording = callingApiService.uploadCallRecording(tenantId, callId, file);
        return ResponseEntity.status(HttpStatus.CREATED).body(recording);
    }

    /**
     * Retries failed recording upload for a specific call session.
     */
    @PostMapping("/calls/{callId}/recording/retry")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'SUPER_ADMIN', 'PLATFORM_ADMIN')")
    public ResponseEntity<WhatsAppCallRecordingDTO> retryCallRecording(@PathVariable String callId) {
        UUID tenantId = tenantResolver.resolveAuthenticatedTenantId();
        WhatsAppCallRecordingDTO recording = callingApiService.retryCallRecording(tenantId, callId);
        return ResponseEntity.ok(recording);
    }

    /**
     * Retrieves aggregated calling analytics for the authenticated tenant.
     */
    @GetMapping("/analytics")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'SUPER_ADMIN', 'PLATFORM_ADMIN') or @perm.has(authentication, 'MODULE_ANALYTICS')")
    public ResponseEntity<WhatsAppCallingAnalyticsDTO> getCallingAnalytics(
            @RequestParam(required = false) String phoneNumberId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to
    ) {
        UUID tenantId = tenantResolver.resolveAuthenticatedTenantId();
        WhatsAppCallingAnalyticsDTO analytics = callingApiService.getAnalytics(tenantId, phoneNumberId, from, to);
        return ResponseEntity.ok(analytics);
    }
}
