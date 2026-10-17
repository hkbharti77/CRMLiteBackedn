package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.models.MetaDataDeletionRequest;
import com.chatcrmlite.backend.models.SecurityNotificationOutbox;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.repositories.MetaDataDeletionRequestRepository;
import com.chatcrmlite.backend.repositories.SecurityNotificationOutboxRepository;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

/**
 * Service managing Meta compliance workflows:
 * 1. Meta Deauthorization Callback (revoking tokens and disconnecting integration).
 * 2. Meta Data Deletion Callback (GDPR compliant erasure, status tracking, and confirmation code generation).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetaComplianceService {

    private final WhatsAppConfigRepository whatsappConfigRepository;
    private final MetaDataDeletionRequestRepository deletionRequestRepository;
    private final SecurityNotificationOutboxRepository securityNotificationOutboxRepository;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * Handles Meta App Deauthorization callback.
     * Revokes access credentials, transitions connection state to DISCONNECTED, and audits the event.
     */
    @Transactional
    public Map<String, Object> handleDeauthorization(String metaUserId, long issuedAt) {
        if (!StringUtils.hasText(metaUserId)) {
            throw new IllegalArgumentException("Meta user_id is required for deauthorization");
        }

        log.info("[MetaCompliance] Processing deauthorization request for Meta user ID: [REDACTED_USER_{}]",
                hashMetaId(metaUserId));

        // 1. Locate all WhatsApp configs linked to this Meta user (or fallback business ID)
        List<WhatsAppConfig> configs = new ArrayList<>(whatsappConfigRepository.findAllByMetaUserId(metaUserId));
        if (configs.isEmpty()) {
            configs.addAll(whatsappConfigRepository.findAllByBusinessId(metaUserId));
        }

        int affectedCount = 0;
        for (WhatsAppConfig config : configs) {
            // Safe revocation of access credentials
            config.setConnectionStatus("DISCONNECTED");
            config.setAccountStatus("DEAUTHORIZED");
            config.setAccountStatusReason("Deauthorized via Meta App Settings at " + (issuedAt > 0 ? issuedAt : Instant.now().getEpochSecond()));
            config.setAccessToken(null); // Wipe encrypted access token to prevent future API calls
            whatsappConfigRepository.save(config);
            affectedCount++;

            // Create persistent security audit entry
            try {
                if (config.getTenant() != null) {
                    SecurityNotificationOutbox audit = SecurityNotificationOutbox.builder()
                            .tenant(config.getTenant())
                            .eventType("META_APP_DEAUTHORIZED")
                            .phoneNumberId(config.getPhoneNumberId())
                            .metaUserId(metaUserId)
                            .sourceEventId("deauth_" + metaUserId + "_" + (issuedAt > 0 ? issuedAt : System.currentTimeMillis()))
                            .description("Meta WhatsApp integration was deauthorized from Meta App Dashboard.")
                            .status("PENDING")
                            .build();
                    securityNotificationOutboxRepository.save(audit);
                }
            } catch (Exception e) {
                log.warn("[MetaCompliance] Could not persist security outbox audit for deauth: {}", e.getMessage());
            }
        }

        log.info("[MetaCompliance] Deauthorization completed. Affected WhatsApp configs: {}", affectedCount);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("configsAffected", affectedCount);
        result.put("message", "Meta app deauthorization processed successfully");
        return result;
    }

    /**
     * Handles Meta Data Deletion callback.
     * Persists request, wipes stored credentials, and returns confirmation code and status URL.
     */
    @Transactional
    public Map<String, String> handleDataDeletion(String metaUserId, String publicBaseUrl) {
        if (!StringUtils.hasText(metaUserId)) {
            throw new IllegalArgumentException("Meta user_id is required for data deletion");
        }

        log.info("[MetaCompliance] Processing data deletion request for Meta user ID: [REDACTED_USER_{}]",
                hashMetaId(metaUserId));

        // 1. Locate all WhatsApp configs linked to this Meta user (or fallback business ID)
        List<WhatsAppConfig> configs = new ArrayList<>(whatsappConfigRepository.findAllByMetaUserId(metaUserId));
        if (configs.isEmpty()) {
            configs.addAll(whatsappConfigRepository.findAllByBusinessId(metaUserId));
        }

        boolean hasActiveCredentials = configs.stream().anyMatch(c ->
                StringUtils.hasText(c.getAccessToken()) || "ACTIVE".equalsIgnoreCase(c.getConnectionStatus()));

        // 2. Idempotency: Return existing completed request only if no active credentials exist
        Optional<MetaDataDeletionRequest> existing = deletionRequestRepository.findFirstByMetaUserIdAndStatus(metaUserId, "COMPLETED");
        if (existing.isPresent() && !hasActiveCredentials) {
            MetaDataDeletionRequest req = existing.get();
            String statusUrl = buildStatusUrl(publicBaseUrl, req.getConfirmationCode());
            log.info("[MetaCompliance] Returning existing completed data deletion confirmation code (no active credentials)");
            return Map.of("url", statusUrl, "confirmation_code", req.getConfirmationCode());
        }

        // 3. Generate cryptographically strong confirmation code
        String confirmationCode = generateSecureConfirmationCode();
        String statusUrl = buildStatusUrl(publicBaseUrl, confirmationCode);

        // 4. Create deletion request record in IN_PROGRESS state
        MetaDataDeletionRequest deletionRequest = MetaDataDeletionRequest.builder()
                .confirmationCode(confirmationCode)
                .metaUserId(metaUserId)
                .tenant(!configs.isEmpty() ? configs.get(0).getTenant() : null)
                .status("IN_PROGRESS")
                .requestedAt(Instant.now())
                .build();
        deletionRequestRepository.save(deletionRequest);

        // 5. Execute actual data erasure on matching tenant configs & related audit records
        int erasedConfigs = 0;
        int anonymizedAudits = 0;
        try {
            for (WhatsAppConfig config : configs) {
                config.setAccessToken(null);
                config.setVerifyToken(null);
                config.setAppSecret(null);
                config.setConnectionStatus("DISCONNECTED");
                config.setAccountStatus("DELETED");
                config.setAccountStatusReason("Personal credentials purged per Meta GDPR Data Deletion request");
                config.setMetaUserId(null);
                whatsappConfigRepository.save(config);
                erasedConfigs++;
            }

            // Anonymize Meta User ID in security audit outbox to comply with GDPR storage limitation
            List<SecurityNotificationOutbox> outboxList = securityNotificationOutboxRepository.findAllByMetaUserId(metaUserId);
            for (SecurityNotificationOutbox entry : outboxList) {
                entry.setMetaUserId("ANONYMIZED_GDPR");
                securityNotificationOutboxRepository.save(entry);
                anonymizedAudits++;
            }

            deletionRequest.setStatus("COMPLETED");
            deletionRequest.setCompletedAt(Instant.now());
            deletionRequest.setDataSummary(String.format(
                    "Purged Meta credentials on %d configs; anonymized %d security audit records.",
                    erasedConfigs, anonymizedAudits));
            deletionRequestRepository.save(deletionRequest);

            log.info("[MetaCompliance] Data deletion successfully completed. Erased configs: {}, Anonymized audits: {}",
                    erasedConfigs, anonymizedAudits);

        } catch (Exception e) {
            log.error("[MetaCompliance] Data deletion encountered error: {}", e.getMessage(), e);
            deletionRequest.setStatus("FAILED");
            deletionRequest.setErrorMessage(e.getMessage());
            deletionRequestRepository.save(deletionRequest);
        }

        Map<String, String> response = new HashMap<>();
        response.put("url", statusUrl);
        response.put("confirmation_code", confirmationCode);
        return response;
    }

    /**
     * Looks up deletion request status by confirmation code.
     */
    @Transactional(readOnly = true)
    public Optional<MetaDataDeletionRequest> getDeletionStatus(String confirmationCode) {
        if (!StringUtils.hasText(confirmationCode)) {
            return Optional.empty();
        }
        return deletionRequestRepository.findByConfirmationCode(confirmationCode.trim());
    }

    private String buildStatusUrl(String publicBaseUrl, String confirmationCode) {
        String base = (publicBaseUrl != null && !publicBaseUrl.isBlank())
                ? publicBaseUrl.replaceAll("/+$", "")
                : "https://api.gyanvaniai.online";
        return base + "/api/v1/integrations/meta/data-deletion/status?code=" + confirmationCode;
    }

    private String generateSecureConfirmationCode() {
        byte[] randomBytes = new byte[16];
        SECURE_RANDOM.nextBytes(randomBytes);
        return HexFormat.of().formatHex(randomBytes) + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private String hashMetaId(String metaUserId) {
        if (metaUserId == null || metaUserId.length() < 4) {
            return "****";
        }
        return metaUserId.substring(metaUserId.length() - 4);
    }
}
