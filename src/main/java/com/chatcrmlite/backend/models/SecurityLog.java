package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "security_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SecurityLog {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    private LogAction action;

    private String status; // SUCCESS, FAILURE
    private String details;
    private String ipAddress;
    private String deviceName;
    private LocalDateTime timestamp;

    @PrePersist
    protected void onCreate() {
        timestamp = LocalDateTime.now();
    }

    public enum LogAction {
        LOGIN_SUCCESS,
        LOGIN_FAILURE,
        LOGOUT,
        PASSWORD_CHANGE,
        IP_WHITELIST_UPDATE,
        ACCOUNT_LOCKED,
        ACCOUNT_UNLOCKED,
        BIOMETRICS_TOGGLED,
        SESSIONS_REVOKED
    }
    public static SecurityLogBuilder builder() {
        return new SecurityLogBuilder();
    }

    public static class SecurityLogBuilder {
        private User user;
        private LogAction action;
        private String status;
        private String details;
        private String ipAddress;
        private String deviceName;

        public SecurityLogBuilder user(User user) { this.user = user; return this; }
        public SecurityLogBuilder action(LogAction action) { this.action = action; return this; }
        public SecurityLogBuilder status(String status) { this.status = status; return this; }
        public SecurityLogBuilder details(String details) { this.details = details; return this; }
        public SecurityLogBuilder ipAddress(String ipAddress) { this.ipAddress = ipAddress; return this; }
        public SecurityLogBuilder deviceName(String deviceName) { this.deviceName = deviceName; return this; }

        public SecurityLog build() {
            SecurityLog log = new SecurityLog();
            log.setUser(user);
            log.setAction(action);
            log.setStatus(status);
            log.setDetails(details);
            log.setIpAddress(ipAddress);
            log.setDeviceName(deviceName);
            return log;
        }
    }

    public void setUser(User user) { this.user = user; }
    public void setAction(LogAction action) { this.action = action; }
    public void setStatus(String status) { this.status = status; }
    public void setDetails(String details) { this.details = details; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }
}
