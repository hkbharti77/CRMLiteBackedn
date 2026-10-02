package com.chatcrmlite.backend.services.google;

import com.chatcrmlite.backend.config.GoogleConfig;
import com.chatcrmlite.backend.models.google.GoogleConnection;
import com.chatcrmlite.backend.models.google.GoogleIntegrationStatus;
import com.chatcrmlite.backend.repositories.GoogleConnectionRepository;
import com.chatcrmlite.backend.repositories.GoogleIntegrationRepository;
import com.chatcrmlite.backend.utils.EncryptionConverter;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Centralized service for Google OAuth2 access token management.
 *
 * ALL Google services (Calendar, Gmail, Drive, Tasks, Sheets, Contacts) must
 * call getValidAccessToken() from here. No refresh logic should be duplicated
 * in individual Google services.
 *
 * Token refresh flow:
 *   1. Check if stored access token is still valid (with 5-min buffer).
 *   2. If expired, use refresh token to get a new access token.
 *   3. Encrypt and persist the new access token + expiry.
 *   4. Return the plaintext access token.
 *
 * On refresh failure (401 from Google):
 *   - Sets integration status to REAUTH_REQUIRED.
 *   - Throws GoogleReauthRequiredException so callers can handle gracefully.
 */
@Service
public class GoogleTokenService {

    private static final Logger log = LoggerFactory.getLogger(GoogleTokenService.class);

    @Autowired private GoogleConnectionRepository connectionRepository;
    @Autowired private GoogleIntegrationRepository integrationRepository;
    @Autowired private GoogleConfig googleConfig;
    @Autowired private EncryptionConverter encryptionConverter;

    /**
     * Returns a valid (non-expired) plaintext access token for the given connection.
     * Refreshes automatically if the current token is expired.
     *
     * @param connectionId the GoogleConnection UUID
     * @return plaintext access token ready for use in Google API calls
     * @throws IllegalStateException if connection is revoked or not found
     * @throws GoogleReauthRequiredException if refresh token is invalid — user must re-authorize
     */
    @Transactional
    public String getValidAccessToken(UUID connectionId) {
        GoogleConnection conn = connectionRepository.findById(connectionId)
                .orElseThrow(() -> new IllegalStateException("Google connection not found: " + connectionId));

        if (conn.getRevokedAt() != null) {
            throw new IllegalStateException("Google connection has been revoked for connectionId=" + connectionId);
        }

        // Token still valid (with 5-minute buffer) — return immediately
        if (!conn.isAccessTokenExpired()) {
            return encryptionConverter.convertToEntityAttribute(conn.getAccessTokenEncrypted());
        }

        // Token expired — refresh using refresh token
        log.info("[GoogleTokenService] Access token expired, refreshing for connectionId={}", connectionId);
        return refreshAndPersist(conn);
    }

    /**
     * Forces a token refresh regardless of current expiry.
     * Called by retry handlers after a 401 response from Google.
     */
    @Transactional
    public String forceRefresh(UUID connectionId) {
        GoogleConnection conn = connectionRepository.findById(connectionId)
                .orElseThrow(() -> new IllegalStateException("Google connection not found: " + connectionId));
        log.info("[GoogleTokenService] Force-refreshing token for connectionId={}", connectionId);
        return refreshAndPersist(conn);
    }

    // ── Private Helpers ────────────────────────────────────────────────────

    private String refreshAndPersist(GoogleConnection conn) {
        String refreshToken = encryptionConverter.convertToEntityAttribute(conn.getRefreshTokenEncrypted());

        if (refreshToken == null || refreshToken.isBlank()) {
            markReauthRequired(conn);
            throw new GoogleReauthRequiredException("No refresh token available for connectionId=" + conn.getId());
        }

        try {
            NetHttpTransport transport = GoogleNetHttpTransport.newTrustedTransport();
            GsonFactory jsonFactory = GsonFactory.getDefaultInstance();

            // Build token refresh request
            GoogleTokenResponse tokenResponse = new com.google.api.client.googleapis.auth.oauth2.GoogleRefreshTokenRequest(
                    transport,
                    jsonFactory,
                    refreshToken,
                    googleConfig.getClientId(),
                    googleConfig.getClientSecret()
            ).execute();

            String newAccessToken = tokenResponse.getAccessToken();
            long expiresIn = tokenResponse.getExpiresInSeconds() != null
                    ? tokenResponse.getExpiresInSeconds() : 3600L;

            // Encrypt and persist the new token
            conn.setAccessTokenEncrypted(encryptionConverter.convertToDatabaseColumn(newAccessToken));
            conn.setExpiresAt(LocalDateTime.now().plusSeconds(expiresIn));

            // If Google returned a new refresh token (rare), persist it too
            if (tokenResponse.getRefreshToken() != null && !tokenResponse.getRefreshToken().isBlank()) {
                conn.setRefreshTokenEncrypted(encryptionConverter.convertToDatabaseColumn(tokenResponse.getRefreshToken()));
            }

            connectionRepository.save(conn);
            log.info("[GoogleTokenService] Token refreshed successfully for connectionId={}", conn.getId());

            return newAccessToken;

        } catch (Exception e) {
            log.error("[GoogleTokenService] Token refresh failed for connectionId={}: {}", conn.getId(), e.getMessage());
            // If refresh fails (e.g., refresh token revoked by user), require re-auth
            markReauthRequired(conn);
            throw new GoogleReauthRequiredException(
                    "Google token refresh failed for connectionId=" + conn.getId() + ": " + e.getMessage());
        }
    }

    private void markReauthRequired(GoogleConnection conn) {
        // Mark all integrations for this connection as REAUTH_REQUIRED
        integrationRepository.findAllByConnectionId(conn.getId()).forEach(integration -> {
            integration.setStatus(GoogleIntegrationStatus.REAUTH_REQUIRED);
            integrationRepository.save(integration);
        });
        log.warn("[GoogleTokenService] Marked connectionId={} as REAUTH_REQUIRED", conn.getId());
    }

    /**
     * Exception thrown when a Google refresh token is invalid/revoked.
     * Callers should surface this to the user as a "reconnect required" prompt.
     */
    public static class GoogleReauthRequiredException extends RuntimeException {
        public GoogleReauthRequiredException(String message) {
            super(message);
        }
    }
}
