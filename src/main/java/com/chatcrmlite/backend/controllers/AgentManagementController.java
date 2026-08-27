package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.LeadRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.team.AgentAnalyticsService;
import com.chatcrmlite.backend.services.team.AgentAssignmentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/team")
public class AgentManagementController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LeadRepository leadRepository;

    @Autowired
    private AgentAssignmentService agentAssignmentService;

    @Autowired
    private AgentAnalyticsService agentAnalyticsService;

    private User getAuthenticatedUser() {
        String email = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Authenticated user not found"));
    }

    @GetMapping("/members")
    public ResponseEntity<List<User>> getTeamMembers() {
        User user = getAuthenticatedUser();
        List<User> members = userRepository.findAllByTenant(user.getTenant());
        return ResponseEntity.ok(members);
    }

    @PatchMapping("/members/{id}/availability")
    public ResponseEntity<Map<String, Object>> updateAvailability(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body) {
        User requester = getAuthenticatedUser();
        User targetUser = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found with id: " + id));

        if (!targetUser.getTenant().getId().equals(requester.getTenant().getId())) {
            return ResponseEntity.status(403).build();
        }

        String statusStr = body.get("availabilityStatus");
        if (statusStr != null) {
            try {
                User.AvailabilityStatus status = User.AvailabilityStatus.valueOf(statusStr.toUpperCase());
                targetUser.setAvailabilityStatus(status);
                userRepository.save(targetUser);
            } catch (Exception e) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid status value: " + statusStr));
            }
        }

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Availability status updated successfully",
                "availabilityStatus", targetUser.getAvailabilityStatus().name()
        ));
    }

    @GetMapping("/analytics/performance")
    public ResponseEntity<List<AgentAnalyticsService.AgentPerformanceDTO>> getTeamPerformance() {
        User user = getAuthenticatedUser();
        List<AgentAnalyticsService.AgentPerformanceDTO> performance = agentAnalyticsService.getTeamPerformanceForTenant(user.getTenant());
        return ResponseEntity.ok(performance);
    }

    @PostMapping("/leads/{leadId}/assign")
    public ResponseEntity<Map<String, Object>> assignLeadToAgent(
            @PathVariable UUID leadId,
            @RequestBody Map<String, String> body) {
        User requester = getAuthenticatedUser();
        if (requester.getTenant() == null || requester.getTenant().getId() == null) {
            return ResponseEntity.status(403).build();
        }

        Lead lead = leadRepository.findById(leadId)
                .orElseThrow(() -> new RuntimeException("Lead not found with id: " + leadId));

        if (lead.getOwner() == null || lead.getOwner().getTenant() == null 
                || !lead.getOwner().getTenant().getId().equals(requester.getTenant().getId())) {
            return ResponseEntity.status(403).build();
        }

        String agentIdStr = body.get("agentId");
        if (agentIdStr == null || agentIdStr.isBlank()) {
            // Auto round-robin assignment
            User assigned = agentAssignmentService.assignLeadRoundRobin(lead);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "assignedAgentId", assigned != null ? assigned.getId().toString() : "none",
                    "assignedAgentName", assigned != null ? (assigned.getFirstName() != null ? assigned.getFirstName() : assigned.getDisplayName()) : "Unassigned"
            ));
        }

        UUID agentId = UUID.fromString(agentIdStr);
        User agent = userRepository.findById(agentId)
                .orElseThrow(() -> new RuntimeException("Agent not found with id: " + agentId));

        if (agent.getTenant() == null || !agent.getTenant().getId().equals(requester.getTenant().getId())) {
            return ResponseEntity.status(403).build();
        }

        lead.setOwner(agent);
        if (lead.getContact() != null) {
            lead.getContact().setOwner(agent);
        }
        leadRepository.save(lead);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "assignedAgentId", agent.getId().toString(),
                "assignedAgentName", agent.getFirstName() != null ? agent.getFirstName() : (agent.getDisplayName() != null ? agent.getDisplayName() : "Agent")
        ));
    }
}
