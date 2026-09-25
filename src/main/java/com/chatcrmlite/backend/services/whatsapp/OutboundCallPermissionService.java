package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.clients.MetaWhatsAppClient;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.models.whatsapp.CallLimitEvent;
import com.chatcrmlite.backend.models.whatsapp.WhatsAppCallPermission;
import com.chatcrmlite.backend.repositories.CallLimitEventRepository;
import com.chatcrmlite.backend.repositories.WhatsAppCallPermissionRepository;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboundCallPermissionService {

    private final WhatsAppCallPermissionRepository permissionRepository;
    private final CallLimitEventRepository eventRepository;
    private final MetaCallingLimitsResolver limitsResolver;
    private final StringRedisTemplate redisTemplate;
    private final MetaWhatsAppClient metaWhatsAppClient;
    private final WhatsAppConfigRepository whatsappConfigRepository;

    /**
     * Records an event-sourced CallLimitEvent in PostgreSQL and increments Redis sliding window counter
     */
    @Transactional
    public void recordLimitEvent(UUID tenantId, String phoneNumberId, String userWaId, String eventType, String callId) {
        CallLimitEvent event = CallLimitEvent.builder()
                .phoneNumberId(phoneNumberId)
                .userWaId(userWaId)
                .eventType(eventType)
                .callId(callId)
                .occurredAt(Instant.now())
                .build();
        event.setTenantId(tenantId);
        eventRepository.save(event);

        // Redis sliding window updates
        String nowStr = String.valueOf(System.currentTimeMillis());
        if ("CALL_INITIATED".equalsIgnoreCase(eventType)) {
            String key = "wa:calling:" + phoneNumberId + ":initiation:24h";
            redisTemplate.opsForList().rightPush(key, nowStr);
            redisTemplate.expire(key, Duration.ofHours(24));
        } else if ("CALL_CONNECTED".equalsIgnoreCase(eventType)) {
            String key = "wa:calling:" + phoneNumberId + ":connected:24h";
            redisTemplate.opsForList().rightPush(key, nowStr);
            redisTemplate.expire(key, Duration.ofHours(24));
        } else if ("PERMISSION_REQUEST".equalsIgnoreCase(eventType) && userWaId != null) {
            String key24h = "wa:permission:" + phoneNumberId + ":" + userWaId + ":requests:24h";
            String key7d = "wa:permission:" + phoneNumberId + ":" + userWaId + ":requests:7d";
            redisTemplate.opsForList().rightPush(key24h, nowStr);
            redisTemplate.expire(key24h, Duration.ofHours(24));
            redisTemplate.opsForList().rightPush(key7d, nowStr);
            redisTemplate.expire(key7d, Duration.ofDays(7));
        }
    }

    /**
     * Checks if tenant/phone number can initiate an outbound call under Meta limits
     */
    public boolean canInitiateOutboundCall(UUID tenantId, String phoneNumberId, String userWaId) {
        MetaCallingLimits limits = limitsResolver.resolve(tenantId, phoneNumberId);

        // 1. Check 24h initiation limit (10,000)
        long initiations24h = eventRepository.countEventsSince(
                tenantId, phoneNumberId, "CALL_INITIATED", Instant.now().minus(Duration.ofHours(24))
        );
        if (initiations24h >= limits.initiationLimit24h()) {
            log.warn("🛑 [OutboundCallPermission] Initiation limit reached for phone={}: {}/{}", phoneNumberId, initiations24h, limits.initiationLimit24h());
            return false;
        }

        // 2. Check 24h connected limit (100 Graph / SIP resolved)
        long connected24h = eventRepository.countEventsSince(
                tenantId, phoneNumberId, "CALL_CONNECTED", Instant.now().minus(Duration.ofHours(24))
        );
        if (connected24h >= limits.connectedLimit24h()) {
            log.warn("🛑 [OutboundCallPermission] Connected call limit reached for phone={}: {}/{}", phoneNumberId, connected24h, limits.connectedLimit24h());
            return false;
        }

        // 3. Verify user call permission
        return isPermissionActive(tenantId, phoneNumberId, userWaId);
    }

    /**
     * Checks if caller has active, unexpired call permission
     */
    public boolean isPermissionActive(UUID tenantId, String phoneNumberId, String userWaId) {
        if (userWaId == null || userWaId.isBlank()) return false;

        Optional<WhatsAppCallPermission> permOpt = permissionRepository
                .findByTenantIdAndPhoneNumberIdAndUserWaId(tenantId, phoneNumberId, userWaId);

        if (permOpt.isEmpty()) return false;
        WhatsAppCallPermission perm = permOpt.get();

        String status = perm.getStatus();
        if (!"GRANTED_TEMPORARY".equalsIgnoreCase(status) && !"GRANTED_PERMANENT".equalsIgnoreCase(status)) {
            return false;
        }

        if (perm.getExpiresAt() != null && perm.getExpiresAt().isBefore(Instant.now())) {
            log.info("⌛ [OutboundCallPermission] Permission expired for userWaId={}", userWaId);
            return false;
        }

        return true;
    }

    /**
     * Reconciles permission status changes received from webhooks
     */
    @Transactional
    public void reconcilePermissionFromWebhook(
            UUID tenantId,
            String phoneNumberId,
            String userWaId,
            String newStatus,
            String permissionType,
            Boolean isPermanent,
            String responseSource,
            Instant expiresAt
    ) {
        WhatsAppCallPermission perm = permissionRepository
                .findByTenantIdAndPhoneNumberIdAndUserWaId(tenantId, phoneNumberId, userWaId)
                .orElseGet(() -> {
                    WhatsAppCallPermission p = WhatsAppCallPermission.builder()
                            .phoneNumberId(phoneNumberId)
                            .userWaId(userWaId)
                            .build();
                    p.setTenantId(tenantId);
                    return p;
                });

        perm.setStatus(newStatus);
        if (permissionType != null) perm.setPermissionType(permissionType);
        if (isPermanent != null) perm.setIsPermanent(isPermanent);
        if (responseSource != null) perm.setResponseSource(responseSource);
        if (expiresAt != null) perm.setExpiresAt(expiresAt);

        if ("GRANTED_TEMPORARY".equalsIgnoreCase(newStatus) || "GRANTED_PERMANENT".equalsIgnoreCase(newStatus)) {
            perm.setGrantedAt(Instant.now());
        } else if ("REVOKED".equalsIgnoreCase(newStatus) || "DENIED".equalsIgnoreCase(newStatus)) {
            perm.setRevokedAt(Instant.now());
        }
        perm.setLastPermissionWebhookAt(Instant.now());

        permissionRepository.save(perm);

        // Evict Redis permission cache
        String cacheKey = "wa:permission:cache:" + phoneNumberId + ":" + userWaId;
        redisTemplate.delete(cacheKey);

        log.info("✅ [OutboundCallPermission] Reconciled permission for userWaId={} status='{}'", userWaId, newStatus);
    }

    /**
     * Sends an interactive Call Permission Request message if within 24h/7d rate limits
     */
    @Transactional
    public boolean requestCallPermission(UUID tenantId, String phoneNumberId, String userWaId, String messageText) {
        MetaCallingLimits limits = limitsResolver.resolve(tenantId, phoneNumberId);

        // Check 24h permission request limit (1 per 24h per user)
        long reqs24h = eventRepository.countUserEventsSince(
                tenantId, phoneNumberId, userWaId, "PERMISSION_REQUEST", Instant.now().minus(Duration.ofHours(24))
        );
        if (reqs24h >= limits.permissionRequestLimit24h()) {
            log.warn("🛑 [OutboundCallPermission] Permission request 24h limit reached for userWaId={}", userWaId);
            return false;
        }

        // Check 7d permission request limit (2 per 7d per user)
        long reqs7d = eventRepository.countUserEventsSince(
                tenantId, phoneNumberId, userWaId, "PERMISSION_REQUEST", Instant.now().minus(Duration.ofDays(7))
        );
        if (reqs7d >= limits.permissionRequestLimit7d()) {
            log.warn("🛑 [OutboundCallPermission] Permission request 7d limit reached for userWaId={}", userWaId);
            return false;
        }

        WhatsAppConfig config = whatsappConfigRepository.findByPhoneNumberId(phoneNumberId).orElse(null);
        if (config == null || config.getAccessToken() == null) {
            log.error("❌ [OutboundCallPermission] WhatsAppConfig missing access token for phone={}", phoneNumberId);
            return false;
        }

        try {
            metaWhatsAppClient.sendCallPermissionRequest(phoneNumberId, config.getAccessToken(), userWaId, messageText);
            recordLimitEvent(tenantId, phoneNumberId, userWaId, "PERMISSION_REQUEST", null);

            // Record PENDING in database
            WhatsAppCallPermission perm = permissionRepository
                    .findByTenantIdAndPhoneNumberIdAndUserWaId(tenantId, phoneNumberId, userWaId)
                    .orElseGet(() -> {
                        WhatsAppCallPermission p = WhatsAppCallPermission.builder()
                                .phoneNumberId(phoneNumberId)
                                .userWaId(userWaId)
                                .build();
                        p.setTenantId(tenantId);
                        return p;
                    });
            perm.setStatus("PENDING");
            perm.setLastPermissionRequestAt(Instant.now());
            permissionRepository.save(perm);

            log.info("📨 [OutboundCallPermission] Sent call permission request to userWaId={}", userWaId);
            return true;
        } catch (Exception e) {
            log.error("❌ [OutboundCallPermission] Failed to send permission request to userWaId={}: {}", userWaId, e.getMessage());
            return false;
        }
    }
}

