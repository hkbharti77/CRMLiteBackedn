package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.models.SecurityNotificationOutbox;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.SecurityNotificationOutboxRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.EmailService;
import com.chatcrmlite.backend.services.websocket.DistributedWebSocketPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class SecurityNotificationWorker {

    private final SecurityNotificationOutboxRepository securityNotificationOutboxRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;
    private final DistributedWebSocketPublisher webSocketPublisher;

    @Scheduled(fixedDelay = 5000)
    public void processSecurityNotifications() {
        List<SecurityNotificationOutbox> pending = securityNotificationOutboxRepository
                .findTop50ByStatusInOrderByCreatedAtAsc(List.of("PENDING", "FAILED"));

        if (pending.isEmpty()) {
            return;
        }

        log.info("[SecurityNotificationWorker] Processing {} pending security notification outbox items", pending.size());

        for (SecurityNotificationOutbox item : pending) {
            processItem(item);
        }
    }

    @Transactional
    public void processItem(SecurityNotificationOutbox item) {
        try {
            // Find recipients: Owners & Admins of the tenant
            List<User> recipients = new ArrayList<>();
            recipients.addAll(userRepository.findByTenantIdAndRole(item.getTenantId(), User.Role.OWNER));
            recipients.addAll(userRepository.findByTenantIdAndRole(item.getTenantId(), User.Role.ADMIN));

            if (recipients.isEmpty()) {
                log.warn("[SecurityNotificationWorker] No OWNER or ADMIN users found for tenantId={}. Checking general users.", item.getTenantId());
            }

            for (User user : recipients) {
                if (user.getEmail() != null && !user.getEmail().isBlank()) {
                    emailService.sendSecurityAlertEmail(
                            user.getEmail(),
                            user.getDisplayName(),
                            item.getEventType(),
                            item.getMetaUserId(),
                            item.getDetails()
                    );
                }
            }

            // Distribute real-time emergency banner over WebSocket to connected tenant clients
            webSocketPublisher.publish(
                    item.getTenantId(),
                    "/topic/security-alerts",
                    Map.of(
                            "type", "SECURITY_ALERT",
                            "eventType", item.getEventType(),
                            "metaUserId", item.getMetaUserId() != null ? item.getMetaUserId() : "",
                            "details", item.getDetails() != null ? item.getDetails() : "",
                            "timestamp", item.getCreatedAt().toString()
                    )
            );

            item.setStatus("PROCESSED");
            item.setProcessedAt(Instant.now());
            item.setErrorMessage(null);
            securityNotificationOutboxRepository.save(item);

            log.info("[SecurityNotificationWorker] Successfully dispatched security alert: id={}, tenantId={}, eventType={}",
                    item.getId(), item.getTenantId(), item.getEventType());

        } catch (Exception e) {
            log.error("[SecurityNotificationWorker] Failed dispatching security alert id={}: {}", item.getId(), e.getMessage(), e);
            item.setRetryCount(item.getRetryCount() + 1);
            item.setErrorMessage(e.getMessage());
            if (item.getRetryCount() >= 5) {
                item.setStatus("DEAD");
            } else {
                item.setStatus("FAILED");
            }
            securityNotificationOutboxRepository.save(item);
        }
    }
}
