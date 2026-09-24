package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.dto.ContactConsentDTO;
import com.chatcrmlite.backend.dto.PublicOptInRequestDTO;
import com.chatcrmlite.backend.dto.UpdateConsentRequestDTO;
import com.chatcrmlite.backend.services.ContactConsentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/public/contacts")
@RequiredArgsConstructor
public class PublicConsentController {

    private final ContactConsentService consentService;

    /**
     * Public opt-in endpoint for web widgets, landing pages, and lead capture forms.
     * Records DPDP/GDPR compliant consent with timestamp, IP address, and user agent.
     */
    @PostMapping("/opt-in")
    public ResponseEntity<ContactConsentDTO> recordPublicOptIn(
            @Valid @RequestBody PublicOptInRequestDTO request,
            HttpServletRequest httpServletRequest) {

        String ipAddress = httpServletRequest.getRemoteAddr();
        String userAgent = httpServletRequest.getHeader("User-Agent");

        ContactConsentDTO result = consentService.recordPublicOptIn(request, ipAddress, userAgent);
        return ResponseEntity.ok(result);
    }

    /**
     * One-click public opt-out (unsubscribe) endpoint.
     */
    @PostMapping("/{contactId}/opt-out")
    public ResponseEntity<ContactConsentDTO> recordPublicOptOut(
            @PathVariable UUID contactId,
            @RequestParam(required = false, defaultValue = "ALL") String channel,
            @RequestParam(required = false, defaultValue = "PUBLIC_UNSUBSCRIBE_LINK") String source,
            @RequestParam(required = false) UUID tenantId,
            HttpServletRequest httpServletRequest) {

        if (tenantId == null) {
            return ResponseEntity.badRequest().build();
        }

        String ipAddress = httpServletRequest.getRemoteAddr();
        String userAgent = httpServletRequest.getHeader("User-Agent");

        UpdateConsentRequestDTO updateReq = UpdateConsentRequestDTO.builder()
                .channel(channel)
                .status("OPTED_OUT")
                .source(source)
                .reason("User clicked public opt-out / unsubscribe link")
                .build();

        ContactConsentDTO result = consentService.updateConsent(
                tenantId,
                contactId,
                updateReq,
                "PUBLIC_UNSUBSCRIBE",
                ipAddress,
                userAgent
        );

        return ResponseEntity.ok(result);
    }
}
