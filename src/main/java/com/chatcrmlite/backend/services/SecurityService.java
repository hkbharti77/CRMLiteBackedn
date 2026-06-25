package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.UserSession;
import com.chatcrmlite.backend.models.SecurityLog;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.repositories.UserSessionRepository;
import com.chatcrmlite.backend.repositories.SecurityLogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class SecurityService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserSessionRepository sessionRepository;

    @Autowired
    private SecurityLogRepository logRepository;

    @Autowired
    private EmailService emailService;

    @Autowired
    private com.chatcrmlite.backend.repositories.TicketRepository ticketRepository;

    @Autowired
    private com.chatcrmlite.backend.repositories.TicketCommentRepository ticketCommentRepository;

    @Autowired
    private com.chatcrmlite.backend.repositories.TicketActivityRepository ticketActivityRepository;

    public int calculateSecurityScore(User user) {
        int score = 0;
        // Password removed as per user request (OTP-only system)
        if (user.getBiometricsEnabled() != null && user.getBiometricsEnabled()) score += 34;
        if (user.getIpWhitelist() != null && !user.getIpWhitelist().isEmpty()) score += 33;
        if (user.getLoginAlertsEnabled() != null && user.getLoginAlertsEnabled()) score += 33;
        return Math.min(score, 100);
    }

    @Transactional
    public void logSecurityEvent(User user, SecurityLog.LogAction action, String status, String details, String ip, String device) {
        SecurityLog log = SecurityLog.builder()
                .user(user)
                .action(action)
                .status(status)
                .details(details)
                .ipAddress(ip)
                .deviceName(device)
                .build();
        logRepository.save(log);
    }

    @Transactional
    public void revokeSession(UUID sessionId) {
        UserSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Session not found"));
        session.setStatus("REVOKED");
        sessionRepository.save(session);
        
        logSecurityEvent(session.getUser(), SecurityLog.LogAction.SESSIONS_REVOKED, "SUCCESS", "Revoked session: " + sessionId, null, null);
    }

    @Transactional
    public void revokeAllSessions(User user) {
        List<UserSession> sessions = sessionRepository.findByUserAndStatus(user, "ACTIVE");
        for (UserSession session : sessions) {
            session.setStatus("REVOKED");
        }
        sessionRepository.saveAll(sessions);
        logSecurityEvent(user, SecurityLog.LogAction.SESSIONS_REVOKED, "SUCCESS", "Revoked all active sessions (Kill Switch)", null, null);
    }

    @Transactional
    public void lockAccount(User user) {
        user.setAccountStatus(User.AccountStatus.LOCKED);
        userRepository.save(user);
        revokeAllSessions(user);
        logSecurityEvent(user, SecurityLog.LogAction.ACCOUNT_LOCKED, "SUCCESS", "Account locked by owner", null, null);
    }

    @Transactional
    public void updateIpWhitelist(User user, java.util.Set<String> newWhitelist) {
        user.setIpWhitelist(newWhitelist);
        userRepository.save(user);
        logSecurityEvent(user, SecurityLog.LogAction.IP_WHITELIST_UPDATE, "SUCCESS", "IP Whitelist updated", null, null);
    }

    @Transactional
    public void deleteStaffUser(User caller, UUID staffId) {
        User targetUser = userRepository.findById(staffId)
                .orElseThrow(() -> new IllegalArgumentException("Staff user not found"));

        // Multi-tenant check
        if (!targetUser.getTenant().getId().equals(caller.getTenant().getId())) {
            throw new IllegalArgumentException("Unauthorized to delete staff from another tenant");
        }

        // Owner check: Cannot delete an OWNER
        if (targetUser.getRole() == User.Role.OWNER) {
            throw new IllegalArgumentException("Cannot delete the tenant owner");
        }

        // Nullify or delete references
        ticketRepository.nullifyAssignedTo(targetUser);
        ticketCommentRepository.nullifyAuthor(targetUser);
        ticketActivityRepository.nullifyUser(targetUser);
        sessionRepository.deleteByUser(targetUser);
        logRepository.deleteByUser(targetUser);

        // Delete user
        userRepository.delete(targetUser);
    }

    @Transactional
    public void updateStaffStatus(User caller, UUID staffId, User.AccountStatus status, String reason) {
        User targetUser = userRepository.findById(staffId)
                .orElseThrow(() -> new IllegalArgumentException("Staff user not found"));

        // Multi-tenant check
        if (!targetUser.getTenant().getId().equals(caller.getTenant().getId())) {
            throw new IllegalArgumentException("Unauthorized to modify staff from another tenant");
        }

        // Cannot modify OWNER status
        if (targetUser.getRole() == User.Role.OWNER) {
            throw new IllegalArgumentException("Cannot modify the tenant owner's status");
        }

        // Role-based authorization: Admin can only modify AGENT status
        if (caller.getRole() == User.Role.ADMIN && targetUser.getRole() != User.Role.AGENT) {
            throw new IllegalArgumentException("Admins can only modify agent statuses");
        }

        boolean isBlocked = (status == User.AccountStatus.LOCKED || status == User.AccountStatus.SUSPENDED || status == User.AccountStatus.DEACTIVATED);

        targetUser.setAccountStatus(status);
        userRepository.save(targetUser);

        if (isBlocked) {
            revokeAllSessions(targetUser);
            logSecurityEvent(targetUser, SecurityLog.LogAction.ACCOUNT_LOCKED, "SUCCESS", "Account blocked by " + caller.getRole() + ". Reason: " + reason, null, null);
        } else {
            logSecurityEvent(targetUser, SecurityLog.LogAction.ACCOUNT_UNLOCKED, "SUCCESS", "Account unblocked by " + caller.getRole() + ". Reason: " + reason, null, null);
        }

        // Find tenant owner
        List<User> tenantUsers = userRepository.findAllByTenant(targetUser.getTenant());
        User tenantOwner = tenantUsers.stream()
                .filter(u -> u.getRole() == User.Role.OWNER)
                .findFirst()
                .orElse(caller);

        // Send notifications
        try {
            emailService.sendStaffStatusChangeEmail(
                targetUser.getEmail(),
                targetUser.getDisplayName(),
                targetUser.getTenant().getBusinessName(),
                isBlocked,
                reason
            );
        } catch (Exception e) {
            System.err.println("Failed to send staff status change email: " + e.getMessage());
        }

        try {
            emailService.sendOwnerStaffStatusNotification(
                tenantOwner.getEmail(),
                tenantOwner.getDisplayName(),
                targetUser.getDisplayName(),
                targetUser.getEmail(),
                isBlocked,
                reason
            );
        } catch (Exception e) {
            System.err.println("Failed to send owner notification email: " + e.getMessage());
        }
    }
}
