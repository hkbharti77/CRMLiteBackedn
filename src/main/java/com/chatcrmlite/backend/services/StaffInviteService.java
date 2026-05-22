package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.models.StaffInvite;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.StaffInviteRepository;
import com.chatcrmlite.backend.repositories.TenantRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class StaffInviteService {
    private static final Logger log = LoggerFactory.getLogger(StaffInviteService.class);

    private final StaffInviteRepository inviteRepository;
    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public StaffInvite createInvitation(UUID tenantId, String email, User.Role role) {
        // Validate target tenant
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found with ID: " + tenantId));

        // Enforce user doesn't already exist
        if (userRepository.findByEmail(email).isPresent()) {
            throw new IllegalArgumentException("User with email " + email + " is already registered.");
        }

        // Deactivate any existing pending invites for this email
        inviteRepository.findByEmailAndStatus(email, StaffInvite.InviteStatus.PENDING)
                .ifPresent(invite -> {
                    invite.setStatus(StaffInvite.InviteStatus.EXPIRED);
                    inviteRepository.save(invite);
                });

        // Generate 32-character unique code
        String inviteCode = UUID.randomUUID().toString().replace("-", "");

        StaffInvite invite = StaffInvite.builder()
                .tenant(tenant)
                .email(email)
                .role(role)
                .inviteCode(inviteCode)
                .status(StaffInvite.InviteStatus.PENDING)
                .expiresAt(LocalDateTime.now().plusHours(48)) // 48 Hours Expiry
                .build();

        StaffInvite saved = inviteRepository.save(invite);
        log.info("[StaffInvite] Generated invitation code={} for email={} under tenantId={}", inviteCode, email, tenantId);
        return saved;
    }

    @Transactional
    public User acceptInvitation(String inviteCode, String password, String displayName, String phone) {
        StaffInvite invite = inviteRepository.findByInviteCode(inviteCode)
                .orElseThrow(() -> new IllegalArgumentException("Invalid invitation code."));

        if (invite.getStatus() != StaffInvite.InviteStatus.PENDING) {
            throw new IllegalStateException("Invitation has already been " + invite.getStatus().name().toLowerCase() + ".");
        }

        if (invite.getExpiresAt().isBefore(LocalDateTime.now())) {
            invite.setStatus(StaffInvite.InviteStatus.EXPIRED);
            inviteRepository.save(invite);
            throw new IllegalStateException("Invitation code has expired.");
        }

        // Register the new user under the SAME Tenant
        User user = User.builder()
                .email(invite.getEmail())
                .password(passwordEncoder.encode(password))
                .tenant(invite.getTenant())
                .displayName(displayName)
                .phone(phone)
                .role(invite.getRole())
                .accountStatus(User.AccountStatus.ACTIVE)
                .onboardingCompleted(true)
                .build();

        User savedUser = userRepository.save(user);

        // Mark invite accepted
        invite.setStatus(StaffInvite.InviteStatus.ACCEPTED);
        inviteRepository.save(invite);

        log.info("[StaffInvite] Invitation accepted successfully. Registered user={} under tenant={}", savedUser.getEmail(), invite.getTenant().getId());
        return savedUser;
    }

    public List<StaffInvite> getTenantInvites(UUID tenantId) {
        return inviteRepository.findByTenantId(tenantId);
    }
}
