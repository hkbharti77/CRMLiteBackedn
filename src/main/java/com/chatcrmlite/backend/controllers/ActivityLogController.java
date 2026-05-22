package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.dto.ActivityLogDTO;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.ActivityLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST Controller for the Unified CRM Activity Timeline.
 *
 * Provides read-only endpoints for the frontend timeline UI.
 * All write operations happen automatically via Event Listeners — never via this API.
 *
 * Base URL: /api/v1/activity-logs
 */
@RestController
@RequestMapping("/api/v1/activity-logs")
@RequiredArgsConstructor
public class ActivityLogController {

    private final ActivityLogService activityLogService;
    private final ContactRepository contactRepository;
    private final UserRepository userRepository;

    // ── Auth helper ────────────────────────────────────────────────────────

    private User getAuthenticatedUser() {
        String email = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    // ── Endpoints ──────────────────────────────────────────────────────────

    /**
     * GET /api/v1/activity-logs/contact/{contactId}
     *
     * Returns the complete CRM timeline for a single contact, newest first.
     * Used by the Contact Profile → Timeline tab in the frontend.
     */
    @GetMapping("/contact/{contactId}")
    public ResponseEntity<List<ActivityLogDTO>> getContactTimeline(@PathVariable UUID contactId) {
        User owner = getAuthenticatedUser();
        Contact contact = contactRepository.findById(contactId)
                .filter(c -> c.getOwner().getId().equals(owner.getId()))
                .orElseThrow(() -> new RuntimeException("Contact not found or access denied"));

        return ResponseEntity.ok(activityLogService.getContactTimeline(contact));
    }

    /**
     * GET /api/v1/activity-logs/contact/{contactId}/recent?limit=10
     *
     * Returns the most recent N activity entries for a contact.
     * Used by the Contact Profile quick-glance sidebar.
     */
    @GetMapping("/contact/{contactId}/recent")
    public ResponseEntity<List<ActivityLogDTO>> getRecentContactActivity(
            @PathVariable UUID contactId,
            @RequestParam(defaultValue = "10") int limit) {

        User owner = getAuthenticatedUser();
        contactRepository.findById(contactId)
                .filter(c -> c.getOwner().getId().equals(owner.getId()))
                .orElseThrow(() -> new RuntimeException("Contact not found or access denied"));

        return ResponseEntity.ok(activityLogService.getRecentContactActivity(contactId, limit));
    }

    /**
     * GET /api/v1/activity-logs/entity/{entityType}/{entityId}
     *
     * Returns full history for a specific CRM entity (e.g., one booking's audit trail).
     */
    @GetMapping("/entity/{entityType}/{entityId}")
    public ResponseEntity<List<ActivityLogDTO>> getEntityHistory(
            @PathVariable String entityType,
            @PathVariable UUID entityId) {

        return ResponseEntity.ok(activityLogService.getEntityHistory(entityType, entityId));
    }

    /**
     * GET /api/v1/activity-logs/feed
     *
     * Returns the global CRM activity feed for the authenticated owner.
     * Used by the dashboard for a real-time activity stream.
     */
    @GetMapping("/feed")
    public ResponseEntity<List<ActivityLogDTO>> getOwnerFeed() {
        User owner = getAuthenticatedUser();
        return ResponseEntity.ok(activityLogService.getOwnerFeed(owner));
    }
}
