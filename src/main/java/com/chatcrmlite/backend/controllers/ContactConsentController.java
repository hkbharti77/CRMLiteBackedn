package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.dto.ConsentSummaryDTO;
import com.chatcrmlite.backend.dto.ContactConsentDTO;
import com.chatcrmlite.backend.dto.UpdateConsentRequestDTO;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.ContactConsentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/contacts")
@RequiredArgsConstructor
public class ContactConsentController {

    private final ContactConsentService consentService;
    private final UserRepository userRepository;

    private User getAuthenticatedUser() {
        String email = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Authenticated user not found"));
    }

    private UUID getTenantId(User user) {
        if (user.getTenant() != null) {
            return user.getTenant().getId();
        }
        throw new RuntimeException("Tenant ID not found for user");
    }

    /**
     * Get detailed consent statuses (WhatsApp, Email, SMS) and audit history for a contact.
     */
    @GetMapping("/{contactId}/consent")
    public ResponseEntity<ContactConsentDTO> getContactConsent(@PathVariable UUID contactId) {
        User user = getAuthenticatedUser();
        return ResponseEntity.ok(consentService.getConsentDTO(getTenantId(user), contactId));
    }

    /**
     * Update WhatsApp, Email, SMS consent or global suppression for a contact.
     */
    @PutMapping("/{contactId}/consent")
    public ResponseEntity<ContactConsentDTO> updateContactConsent(
            @PathVariable UUID contactId,
            @Valid @RequestBody UpdateConsentRequestDTO request,
            HttpServletRequest httpServletRequest) {

        User user = getAuthenticatedUser();
        String performedBy = user.getEmail();
        String ipAddress = httpServletRequest.getRemoteAddr();
        String userAgent = httpServletRequest.getHeader("User-Agent");

        ContactConsentDTO updated = consentService.updateConsent(
                getTenantId(user),
                contactId,
                request,
                performedBy,
                ipAddress,
                userAgent
        );

        return ResponseEntity.ok(updated);
    }

    /**
     * Get aggregate tenant consent metrics for WhatsApp, Email, & SMS.
     */
    @GetMapping("/consent/summary")
    public ResponseEntity<ConsentSummaryDTO> getConsentSummary() {
        User user = getAuthenticatedUser();
        return ResponseEntity.ok(consentService.getConsentSummary(getTenantId(user)));
    }
}
