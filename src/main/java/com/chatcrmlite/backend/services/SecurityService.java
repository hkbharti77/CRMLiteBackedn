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
        user.setAccountStatus("LOCKED");
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
}
