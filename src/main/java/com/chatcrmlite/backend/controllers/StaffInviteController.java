package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.models.StaffInvite;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.StaffInviteService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/invites")
@RequiredArgsConstructor
public class StaffInviteController {

    private final StaffInviteService inviteService;
    private final UserRepository userRepository;

    @PostMapping("/send")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<?> sendInvite(@RequestBody InviteRequest request) {
        String email = getAuthenticatedUserEmail();
        User currentUser = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Authenticated user not found."));

        UUID tenantId = currentUser.getTenant().getId();
        StaffInvite invite = inviteService.createInvitation(tenantId, request.getEmail(), User.Role.valueOf(request.getRole()));

        return ResponseEntity.ok(new InviteResponse(
                invite.getInviteCode(),
                invite.getEmail(),
                invite.getRole().name(),
                invite.getStatus().name()
        ));
    }

    @PostMapping("/accept")
    public ResponseEntity<?> acceptInvite(@RequestBody AcceptRequest request) {
        User registered = inviteService.acceptInvitation(
                request.getInviteCode(),
                request.getPassword(),
                request.getDisplayName(),
                request.getPhone()
        );

        return ResponseEntity.ok(new MessageResponse("Staff registered successfully with email: " + registered.getEmail()));
    }

    @GetMapping
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<List<StaffInvite>> listInvites() {
        String email = getAuthenticatedUserEmail();
        User currentUser = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Authenticated user not found."));

        UUID tenantId = currentUser.getTenant().getId();
        List<StaffInvite> invites = inviteService.getTenantInvites(tenantId);
        return ResponseEntity.ok(invites);
    }

    private String getAuthenticatedUserEmail() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof UserDetails) {
            return ((UserDetails) principal).getUsername();
        } else {
            return principal.toString();
        }
    }

    public static class InviteRequest {
        private String email;
        private String role; // ADMIN or AGENT

        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
    }

    public static class InviteResponse {
        private String inviteCode;
        private String email;
        private String role;
        private String status;

        public InviteResponse(String inviteCode, String email, String role, String status) {
            this.inviteCode = inviteCode;
            this.email = email;
            this.role = role;
            this.status = status;
        }

        public String getInviteCode() { return inviteCode; }
        public String getEmail() { return email; }
        public String getRole() { return role; }
        public String getStatus() { return status; }
    }

    public static class AcceptRequest {
        private String inviteCode;
        private String password;
        private String displayName;
        private String phone;

        public String getInviteCode() { return inviteCode; }
        public void setInviteCode(String inviteCode) { this.inviteCode = inviteCode; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public String getPhone() { return phone; }
        public void setPhone(String phone) { this.phone = phone; }
    }

    public static class MessageResponse {
        private String message;
        public MessageResponse(String message) { this.message = message; }
        public String getMessage() { return message; }
    }
}
