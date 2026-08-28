package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.livechat.TenantLiveChatSettings;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.TenantLiveChatSettingsRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.livechat.LiveChatAuthorizationService;
import com.chatcrmlite.backend.services.livechat.LiveChatPresenceService;
import com.chatcrmlite.backend.services.livechat.LiveSupportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/livechat")
public class LiveChatController {

    @Autowired private LiveSupportService liveSupportService;
    @Autowired private LiveChatPresenceService presenceService;
    @Autowired private LiveChatAuthorizationService authorizationService;
    @Autowired private UserRepository userRepository;
    @Autowired private ContactRepository contactRepository;
    @Autowired private TenantLiveChatSettingsRepository settingsRepository;

    private User getAuthenticatedUser(Authentication auth) {
        if (auth == null || auth.getName() == null) {
            throw new SecurityException("Unauthorized");
        }
        return userRepository.findByEmailWithTenant(auth.getName())
                .orElseThrow(() -> new SecurityException("Authenticated user not found"));
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<Map<String, Object>> heartbeat(Authentication auth) {
        User user = getAuthenticatedUser(auth);
        presenceService.updateHeartbeat(user);
        return ResponseEntity.ok(Map.of("status", "OK", "lastSeenAt", user.getLastSeenAt().toString()));
    }

    @PutMapping("/availability")
    public ResponseEntity<Map<String, Object>> setAvailability(Authentication auth, @RequestBody Map<String, String> payload) {
        User user = getAuthenticatedUser(auth);
        String statusStr = payload.get("status");
        if (statusStr == null) return ResponseEntity.badRequest().build();

        User.AvailabilityStatus status = User.AvailabilityStatus.valueOf(statusStr.toUpperCase());
        presenceService.updateAvailabilityStatus(user, status);
        return ResponseEntity.ok(Map.of("status", "OK", "availabilityStatus", user.getAvailabilityStatus().name()));
    }

    @PostMapping("/contacts/{id}/takeover")
    public ResponseEntity<Map<String, Object>> takeoverChat(Authentication auth, @PathVariable UUID id, @RequestBody Map<String, Object> payload) {
        User user = getAuthenticatedUser(auth);
        String reason = (String) payload.get("reason");
        Boolean forceTakeover = payload.get("forceTakeover") != null ? (Boolean) payload.get("forceTakeover") : false;
        String requestId = UUID.randomUUID().toString();

        liveSupportService.takeoverChat(id, user, reason, forceTakeover, requestId);
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "Chat successfully taken over"));
    }

    @PostMapping("/contacts/{id}/transfer")
    public ResponseEntity<Map<String, Object>> transferChat(Authentication auth, @PathVariable UUID id, @RequestBody Map<String, String> payload) {
        User user = getAuthenticatedUser(auth);
        String targetUserIdStr = payload.get("targetUserId");
        String reason = payload.get("reason");
        if (targetUserIdStr == null) return ResponseEntity.badRequest().build();

        UUID targetUserId = UUID.fromString(targetUserIdStr);
        User targetUser = userRepository.findById(targetUserId).orElseThrow(() -> new IllegalArgumentException("Target user not found"));
        
        if (user.getTenant() == null || targetUser.getTenant() == null || !user.getTenant().getId().equals(targetUser.getTenant().getId())) {
            return ResponseEntity.status(403).body(Map.of("error", "Target user does not belong to the same tenant"));
        }
        
        String requestId = UUID.randomUUID().toString();

        liveSupportService.transferChat(id, targetUser, user, reason, requestId);
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "Chat transferred successfully"));
    }

    @PostMapping("/contacts/{id}/resolve")
    public ResponseEntity<Map<String, Object>> resolveChat(Authentication auth, @PathVariable UUID id) {
        User user = getAuthenticatedUser(auth);
        String requestId = UUID.randomUUID().toString();

        liveSupportService.resolveChat(id, user, requestId);
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "Chat resolved successfully"));
    }

    @GetMapping("/contacts")
    public ResponseEntity<List<Map<String, Object>>> getLiveChatContacts(Authentication auth) {
        User user = getAuthenticatedUser(auth);
        Tenant tenant = user.getTenant();
        if (tenant == null) return ResponseEntity.ok(Collections.emptyList());

        List<Contact> contacts = contactRepository.findAllByTenant(tenant);

        List<Map<String, Object>> result = contacts.stream()
                .filter(c -> authorizationService.canAccessContact(c, user))
                .map(c -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("id", c.getId());
                    map.put("waId", c.getWaId());
                    map.put("name", c.getName());
                    map.put("email", c.getEmail());
                    map.put("botPaused", c.isBotPaused());
                    map.put("supportState", c.getSupportState().name());
                    map.put("assignedAgentId", c.getAssignedAgent() != null ? c.getAssignedAgent().getId() : null);
                    map.put("assignedAgentName", c.getAssignedAgent() != null ? c.getAssignedAgent().getDisplayName() : null);
                    map.put("isLockedToOtherAgent", c.getAssignedAgent() != null && !c.getAssignedAgent().getId().equals(user.getId()) && !authorizationService.isAdminOrOwner(user));
                    return map;
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    @GetMapping("/settings")
    public ResponseEntity<TenantLiveChatSettings> getSettings(Authentication auth) {
        User user = getAuthenticatedUser(auth);
        TenantLiveChatSettings settings = settingsRepository.findByTenant(user.getTenant())
                .orElseGet(() -> settingsRepository.save(new TenantLiveChatSettings(user.getTenant())));
        return ResponseEntity.ok(settings);
    }

    @PutMapping("/settings")
    public ResponseEntity<TenantLiveChatSettings> updateSettings(Authentication auth, @RequestBody TenantLiveChatSettings updated) {
        User user = getAuthenticatedUser(auth);
        if (!authorizationService.isAdminOrOwner(user)) {
            return ResponseEntity.status(403).build();
        }

        TenantLiveChatSettings settings = settingsRepository.findByTenant(user.getTenant())
                .orElseGet(() -> new TenantLiveChatSettings(user.getTenant()));

        if (updated.getMaxConcurrentChats() != null) settings.setMaxConcurrentChats(updated.getMaxConcurrentChats());
        if (updated.getSlaMinutes() != null) settings.setSlaMinutes(updated.getSlaMinutes());
        if (updated.getHeartbeatTimeoutSeconds() != null) settings.setHeartbeatTimeoutSeconds(updated.getHeartbeatTimeoutSeconds());
        if (updated.getRoutingStrategy() != null) settings.setRoutingStrategy(updated.getRoutingStrategy());
        if (updated.getAllowForcedTakeover() != null) settings.setAllowForcedTakeover(updated.getAllowForcedTakeover());
        if (updated.getAllowAgentTransfer() != null) settings.setAllowAgentTransfer(updated.getAllowAgentTransfer());

        settingsRepository.save(settings);
        return ResponseEntity.ok(settings);
    }
}
