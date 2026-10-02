package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.models.UserDevice;
import com.chatcrmlite.backend.repositories.UserDeviceRepository;
import com.chatcrmlite.backend.services.google.GoogleAuditService;
import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.SendResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for sending Firebase Cloud Messaging (FCM) Web & Mobile push notifications.
 * Supports multicast push to all active devices of a user, automatic pruning of dead/unregistered tokens,
 * and graceful fallback logging when credentials are not yet configured.
 */
@Service
public class FcmService {

    private static final Logger log = LoggerFactory.getLogger(FcmService.class);

    private final FirebaseApp firebaseApp;
    private final UserDeviceRepository userDeviceRepository;
    private final GoogleAuditService auditService;

    @Autowired
    public FcmService(@Autowired(required = false) FirebaseApp firebaseApp,
                      UserDeviceRepository userDeviceRepository,
                      @Autowired(required = false) GoogleAuditService auditService) {
        this.firebaseApp = firebaseApp;
        this.userDeviceRepository = userDeviceRepository;
        this.auditService = auditService;
    }

    public boolean isConfigured() {
        return firebaseApp != null;
    }

    /**
     * Sends push notification to all active devices registered by the specified user.
     */
    @Async
    @Transactional
    public void sendToUser(UUID userId, String title, String body, Map<String, String> data) {
        if (userId == null) return;

        List<UserDevice> devices = userDeviceRepository.findAllActiveByUserId(userId);
        if (devices.isEmpty()) {
            log.debug("[FcmService] No active registered devices for userId={}. Skipping push.", userId);
            return;
        }

        List<String> tokens = devices.stream()
                .map(UserDevice::getFcmToken)
                .filter(t -> t != null && !t.isBlank())
                .collect(Collectors.toList());

        if (tokens.isEmpty()) return;

        if (firebaseApp == null) {
            log.info("[FcmService (Mock)] Would send push to {} devices for userId={}: title='{}' body='{}'",
                    tokens.size(), userId, title, body);
            return;
        }

        long startTime = System.currentTimeMillis();
        try {
            MulticastMessage.Builder messageBuilder = MulticastMessage.builder()
                    .addAllTokens(tokens)
                    .setNotification(Notification.builder()
                            .setTitle(title)
                            .setBody(body)
                            .build());

            if (data != null && !data.isEmpty()) {
                messageBuilder.putAllData(data);
            }

            MulticastMessage message = messageBuilder.build();
            BatchResponse response = FirebaseMessaging.getInstance(firebaseApp).sendEachForMulticast(message);

            int successCount = response.getSuccessCount();
            int failureCount = response.getFailureCount();

            log.info("[FcmService] Sent push to userId={} devices: success={} failure={}",
                    userId, successCount, failureCount);

            // Stale device pruning
            List<SendResponse> responses = response.getResponses();
            for (int i = 0; i < responses.size(); i++) {
                SendResponse sr = responses.get(i);
                if (!sr.isSuccessful()) {
                    String errCode = sr.getException() != null ? sr.getException().getMessagingErrorCode().name() : "UNKNOWN";
                    log.warn("[FcmService] Device token failed for userId={} error={}", userId, errCode);
                    if ("UNREGISTERED".equalsIgnoreCase(errCode) || "INVALID_ARGUMENT".equalsIgnoreCase(errCode)) {
                        UserDevice failedDevice = devices.get(i);
                        userDeviceRepository.revokeById(failedDevice.getId(), LocalDateTime.now());
                        log.info("[FcmService] Revoked stale device token id={} for userId={}", failedDevice.getId(), userId);
                    }
                }
            }

            if (auditService != null) {
                auditService.logSuccess(userId, "FCM", "SEND_PUSH", "fcm.googleapis.com", System.currentTimeMillis() - startTime);
            }
        } catch (Exception ex) {
            log.error("[FcmService] Failed to send push notification to userId={}: {}", userId, ex.getMessage());
            if (auditService != null) {
                auditService.logFailure(userId, "FCM", "SEND_PUSH", "fcm.googleapis.com", System.currentTimeMillis() - startTime, ex, 0);
            }
        }
    }

    /**
     * Sends a test push notification to the user's active devices.
     */
    public Map<String, Object> sendTestNotification(UUID userId) {
        List<UserDevice> devices = userDeviceRepository.findAllActiveByUserId(userId);
        Map<String, Object> result = new HashMap<>();
        result.put("activeDeviceCount", devices.size());
        result.put("isFirebaseConfigured", isConfigured());

        if (devices.isEmpty()) {
            result.put("sent", false);
            result.put("message", "No active devices registered. Please enable notifications in this browser first.");
            return result;
        }

        Map<String, String> data = Map.of(
                "type", "TEST_NOTIFICATION",
                "timestamp", LocalDateTime.now().toString(),
                "url", "/settings/integrations"
        );

        sendToUser(userId, "🔔 CRMLite Push Notification", "Push notifications are successfully connected and working!", data);

        result.put("sent", true);
        result.put("message", "Test push notification dispatched to " + devices.size() + " active device(s).");
        return result;
    }
}
