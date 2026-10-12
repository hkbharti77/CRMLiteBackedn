package com.chatcrmlite.backend.services.livechat;

import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@Transactional
public class LiveChatPresenceService {
    private static final Logger log = LoggerFactory.getLogger(LiveChatPresenceService.class);

    @Autowired
    private UserRepository userRepository;

    @Transactional
    public void updateHeartbeat(User user) {
        if (user == null || user.getId() == null) return;
        LocalDateTime now = LocalDateTime.now();
        user.setLastSeenAt(now);
        userRepository.updateLastSeenAt(user.getId(), now);
    }

    @Transactional
    public void updateAvailabilityStatus(User user, User.AvailabilityStatus status) {
        if (user == null || status == null || user.getId() == null) return;
        LocalDateTime now = LocalDateTime.now();
        user.setAvailabilityStatus(status);
        user.setLastSeenAt(now);
        userRepository.updateAvailabilityAndLastSeen(user.getId(), status, now);
        log.info("User {} availability status updated to {}", user.getEmail(), status);
    }

    @Transactional(readOnly = true)
    public boolean isRoutable(User user, int heartbeatTimeoutSeconds) {
        if (user == null) return false;
        if (user.getAccountStatus() != User.AccountStatus.ACTIVE) return false;
        if (user.getAvailabilityStatus() != User.AvailabilityStatus.AVAILABLE) return false;

        if (user.getLastSeenAt() == null) return true; // Default if not yet recorded
        return user.getLastSeenAt().isAfter(LocalDateTime.now().minusSeconds(heartbeatTimeoutSeconds));
    }
}
