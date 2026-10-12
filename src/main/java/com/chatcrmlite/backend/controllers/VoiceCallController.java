package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.dto.voice.VoiceTestCallRequestDTO;
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
 * REST API Controller for Voice AI Calls, Transcripts, and Analytics.
 * Conforms to Gap 4 specification: /api/v1/voice/calls, /turns, /analytics, /test-call.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/voice")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class VoiceCallController {

    private final WhatsAppCallingApiService callingApiService;
    private final TenantResolver tenantResolver;

    @GetMapping("/calls")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'AGENT', 'SUPER_ADMIN', 'PLATFORM_ADMIN') or @perm.has(authentication, 'MODULE_INBOX') or @perm.has(authentication, 'MODULE_VOICE')")
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

    @GetMapping("/calls/{callId}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'AGENT', 'SUPER_ADMIN', 'PLATFORM_ADMIN') or @perm.has(authentication, 'MODULE_INBOX') or @perm.has(authentication, 'MODULE_VOICE')")
    public ResponseEntity<WhatsAppCallDetailDTO> getCallDetails(@PathVariable String callId) {
        UUID tenantId = tenantResolver.resolveAuthenticatedTenantId();
        WhatsAppCallDetailDTO details = callingApiService.getCallDetails(tenantId, callId);
        return ResponseEntity.ok(details);
    }

    @GetMapping("/calls/{callId}/turns")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'AGENT', 'SUPER_ADMIN', 'PLATFORM_ADMIN') or @perm.has(authentication, 'MODULE_INBOX') or @perm.has(authentication, 'MODULE_VOICE')")
    public ResponseEntity<Page<WhatsAppCallTurnDTO>> getCallTurns(
            @PathVariable String callId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        UUID tenantId = tenantResolver.resolveAuthenticatedTenantId();
        Page<WhatsAppCallTurnDTO> turns = callingApiService.getCallTurns(tenantId, callId, page, size);
        return ResponseEntity.ok(turns);
    }

    @GetMapping("/analytics")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'SUPER_ADMIN', 'PLATFORM_ADMIN') or @perm.has(authentication, 'MODULE_ANALYTICS') or @perm.has(authentication, 'MODULE_VOICE')")
    public ResponseEntity<WhatsAppCallingAnalyticsDTO> getCallingAnalytics(
            @RequestParam(required = false) String phoneNumberId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to
    ) {
        UUID tenantId = tenantResolver.resolveAuthenticatedTenantId();
        WhatsAppCallingAnalyticsDTO analytics = callingApiService.getAnalytics(tenantId, phoneNumberId, from, to);
        return ResponseEntity.ok(analytics);
    }

    @PostMapping("/test-call")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'SUPER_ADMIN', 'PLATFORM_ADMIN')")
    public ResponseEntity<WhatsAppCallResponse> initiateTestCall(
            @Valid @RequestBody VoiceTestCallRequestDTO request
    ) {
        UUID tenantId = tenantResolver.resolveAuthenticatedTenantId();
        InitiateWhatsAppCallRequest initRequest = InitiateWhatsAppCallRequest.builder()
                .toWaId(request.getToPhone())
                .phoneNumberId(request.getPhoneNumberId())
                .idempotencyKey(UUID.randomUUID().toString())
                .bizOpaqueCallbackData("test-call-voice-dashboard")
                .build();
        WhatsAppCallResponse response = callingApiService.initiateOutboundCall(tenantId, initRequest, null);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
