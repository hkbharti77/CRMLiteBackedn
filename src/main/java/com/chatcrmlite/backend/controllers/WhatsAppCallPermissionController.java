package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.dto.whatsapp.calling.CallPermissionResponseDTO;
import com.chatcrmlite.backend.dto.whatsapp.calling.RequestCallPermissionRequest;
import com.chatcrmlite.backend.services.whatsapp.WhatsAppCallingApiService;
import com.chatcrmlite.backend.utils.TenantResolver;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST API Controller for WhatsApp Outbound Call Permissions.
 * Exposes permission request triggers and status checks under strict tenant isolation.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/voice/whatsapp/permissions")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class WhatsAppCallPermissionController {

    private final WhatsAppCallingApiService callingApiService;
    private final TenantResolver tenantResolver;

    /**
     * Sends an interactive Call Permission Request message to a WhatsApp user.
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'SUPER_ADMIN', 'PLATFORM_ADMIN') or @perm.has(authentication, 'SETTINGS_WHATSAPP')")
    public ResponseEntity<CallPermissionResponseDTO> requestPermission(
            @Valid @RequestBody RequestCallPermissionRequest request
    ) {
        UUID tenantId = tenantResolver.resolveAuthenticatedTenantId();
        CallPermissionResponseDTO response = callingApiService.requestPermission(tenantId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Retrieves outbound call permission status for a contact ID or phone number.
     *
     * @param contactId Contact UUID or phone number string
     * @param phoneNumberId Optional specific tenant phone number ID
     */
    @GetMapping("/{contactId}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'AGENT', 'SUPER_ADMIN', 'PLATFORM_ADMIN') or @perm.has(authentication, 'MODULE_INBOX')")
    public ResponseEntity<CallPermissionResponseDTO> getPermission(
            @PathVariable String contactId,
            @RequestParam(required = false) String phoneNumberId
    ) {
        UUID tenantId = tenantResolver.resolveAuthenticatedTenantId();
        CallPermissionResponseDTO response = callingApiService.getPermission(tenantId, contactId, phoneNumberId);
        return ResponseEntity.ok(response);
    }
}
