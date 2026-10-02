package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.UserDevice;
import com.chatcrmlite.backend.repositories.UserDeviceRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.FcmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/v1/user/devices")
public class UserDeviceController {

    private static final Logger log = LoggerFactory.getLogger(UserDeviceController.class);

    private final UserDeviceRepository userDeviceRepository;
    private final UserRepository userRepository;
    private final FcmService fcmService;

    @Autowired
    public UserDeviceController(UserDeviceRepository userDeviceRepository,
                                UserRepository userRepository,
                                FcmService fcmService) {
        this.userDeviceRepository = userDeviceRepository;
        this.userRepository = userRepository;
        this.fcmService = fcmService;
    }

    private User getAuthenticatedUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found"));
    }

    /**
     * Registers or refreshes a device's FCM push token using its unique installation ID.
     */
    @PostMapping
    public ResponseEntity<?> registerDevice(@RequestBody Map<String, String> payload) {
        User user = getAuthenticatedUser();

        String installationId = payload.get("installationId");
        String fcmToken = payload.get("fcmToken");
        String platform = payload.getOrDefault("platform", "WEB");
        String browser = payload.get("browser");
        String deviceName = payload.get("deviceName");

        if (installationId == null || installationId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "installationId is required"));
        }
        if (fcmToken == null || fcmToken.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "fcmToken is required"));
        }

        UserDevice device = userDeviceRepository.findByInstallationId(installationId)
                .orElse(new UserDevice());

        device.setUserId(user.getId());
        if (user.getTenant() != null) {
            device.setTenantId(user.getTenant().getId());
        }
        device.setInstallationId(installationId);
        device.setFcmToken(fcmToken);
        device.setPlatform(platform);
        device.setBrowser(browser);
        device.setDeviceName(deviceName != null ? deviceName : (platform + " - " + (browser != null ? browser : "Browser")));
        device.setLastSeenAt(LocalDateTime.now());
        device.setRevokedAt(null); // Re-activate if was previously revoked

        UserDevice saved = userDeviceRepository.save(device);
        log.info("[UserDeviceController] Registered device id={} for userId={}", saved.getId(), user.getId());

        return ResponseEntity.ok(Map.of(
                "success", true,
                "deviceId", saved.getId(),
                "installationId", saved.getInstallationId(),
                "lastSeenAt", saved.getLastSeenAt()
        ));
    }

    /**
     * Lists active registered devices for the current authenticated user.
     */
    @GetMapping
    public ResponseEntity<?> listDevices() {
        User user = getAuthenticatedUser();
        List<UserDevice> devices = userDeviceRepository.findAllActiveByUserId(user.getId());

        List<Map<String, Object>> deviceDtos = new ArrayList<>();
        for (UserDevice d : devices) {
            Map<String, Object> dto = new HashMap<>();
            dto.put("id", d.getId());
            dto.put("installationId", d.getInstallationId());
            dto.put("deviceName", d.getDeviceName());
            dto.put("platform", d.getPlatform());
            dto.put("browser", d.getBrowser());
            dto.put("lastSeenAt", d.getLastSeenAt());
            dto.put("createdAt", d.getCreatedAt());
            deviceDtos.add(dto);
        }

        return ResponseEntity.ok(Map.of(
                "devices", deviceDtos,
                "totalCount", deviceDtos.size(),
                "isFirebaseConfigured", fcmService.isConfigured()
        ));
    }

    /**
     * Revokes a registered device (e.g. on user logout or when user turns off notifications).
     */
    @DeleteMapping("/{installationId}")
    public ResponseEntity<?> revokeDevice(@PathVariable String installationId) {
        User user = getAuthenticatedUser();

        userDeviceRepository.findByInstallationId(installationId).ifPresent(d -> {
            if (user.getId().equals(d.getUserId())) {
                d.setRevokedAt(LocalDateTime.now());
                userDeviceRepository.save(d);
                log.info("[UserDeviceController] Revoked device installationId={} for userId={}", installationId, user.getId());
            }
        });

        return ResponseEntity.ok(Map.of("success", true, "revokedInstallationId", installationId));
    }

    /**
     * Triggers an immediate test push notification to all active devices of the authenticated user.
     */
    @PostMapping("/test")
    public ResponseEntity<?> sendTestPush() {
        User user = getAuthenticatedUser();
        Map<String, Object> testResult = fcmService.sendTestNotification(user.getId());
        return ResponseEntity.ok(testResult);
    }
}
