package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.config.GoogleConfig;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.google.*;
import com.chatcrmlite.backend.repositories.GoogleConnectionRepository;
import com.chatcrmlite.backend.repositories.GoogleIntegrationRepository;
import com.chatcrmlite.backend.repositories.GoogleSyncRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.google.GmailService;
import com.chatcrmlite.backend.services.google.GoogleContactsService;
import com.chatcrmlite.backend.services.google.GoogleDriveService;
import com.chatcrmlite.backend.services.google.GoogleSheetsService;
import com.chatcrmlite.backend.services.google.GoogleTasksService;
import com.chatcrmlite.backend.utils.EncryptionConverter;
import org.springframework.web.multipart.MultipartFile;
import com.google.api.client.auth.oauth2.TokenResponse;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.MemoryDataStoreFactory;
import jakarta.servlet.http.HttpServletResponse;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Google OAuth Integration controller.
 *
 * Security design:
 * - Frontend sends ?integration=CALENDAR (feature name only)
 * - Backend resolves scope via GoogleIntegrationType enum (single source of truth)
 * - OAuth state is single-use (Redis fetch-and-delete) — prevents replay attacks
 * - Granted scopes are verified after callback — PARTIAL status if user denied some
 * - Disconnect calls Google revocation endpoint before clearing local tokens
 */
@RestController
@RequestMapping("/api/v1/integrations/google")
public class IntegrationController {

    private static final Logger log = LoggerFactory.getLogger(IntegrationController.class);
    private static final GsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();

    @Autowired private GoogleConfig googleConfig;
    @Autowired private UserRepository userRepository;
    @Autowired private GoogleConnectionRepository connectionRepository;
    @Autowired private GoogleIntegrationRepository integrationRepository;
    @Autowired private GoogleSyncRepository syncRepository;
    @Autowired private RedissonClient redissonClient;
    @Autowired private EncryptionConverter encryptionConverter;
    @Autowired private GmailService gmailService;
    @Autowired private GoogleContactsService googleContactsService;
    @Autowired private GoogleTasksService googleTasksService;
    @Autowired private GoogleDriveService googleDriveService;
    @Autowired private GoogleSheetsService googleSheetsService;

    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${app.frontend.url:http://localhost:5173}")
    private String frontendUrl;

    // ── Helper ─────────────────────────────────────────────────────────────

    private User getAuthenticatedUser() {
        String email = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Authenticated user not found"));
    }

    // ── Auth URL ───────────────────────────────────────────────────────────

    /**
     * Returns the Google OAuth URL the frontend should redirect the user to.
     * The integration param is a feature name (CALENDAR, GMAIL, etc.) — backend maps to scope.
     *
     * GET /api/v1/integrations/google/auth-url?integration=CALENDAR
     */
    @GetMapping("/auth-url")
    public ResponseEntity<Map<String, String>> getAuthUrl(
            @RequestParam String integration) {
        try {
            User user = getAuthenticatedUser();

            // Resolve integration name to GoogleIntegrationType (backend controls scope)
            GoogleIntegrationType integrationType;
            try {
                integrationType = GoogleIntegrationType.valueOf(integration.toUpperCase());
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Unknown integration type: " + integration));
            }

            // Generate secure random state + store in Redis (10-min TTL, single-use)
            byte[] stateBytes = new byte[32];
            secureRandom.nextBytes(stateBytes);
            String state = Base64.getUrlEncoder().withoutPadding().encodeToString(stateBytes);

            OAuthStatePayload payload = new OAuthStatePayload(user.getId(), integrationType);
            RBucket<OAuthStatePayload> bucket = redissonClient.getBucket("oauth:state:" + state);
            bucket.set(payload, Duration.ofMinutes(10));

            // Mark integration as PENDING
            upsertIntegrationStatus(user.getId(), integrationType, GoogleIntegrationStatus.PENDING);

            // Build OAuth URL — backend resolves scope from enum
            String scope = integrationType.getOauthScope();
            GoogleAuthorizationCodeFlow flow = buildFlow(scope);

            // Standard params: access_type=offline, include_granted_scopes=true
            // prompt=consent only on explicit re-connect (always use here for first-time + re-connect)
            String url = flow.newAuthorizationUrl()
                    .setRedirectUri(googleConfig.getRedirectUri())
                    .setState(state)
                    .setAccessType("offline")
                    .set("include_granted_scopes", "true")
                    .set("prompt", "consent")  // required to get refresh token on every auth
                    .build();

            log.info("[IntegrationController] Auth URL generated for userId={} integration={}", user.getId(), integrationType);
            return ResponseEntity.ok(Map.of("url", url));

        } catch (Exception e) {
            log.error("[IntegrationController] Failed to build auth URL for integration={}", integration, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to build authorization URL: " + e.getMessage()));
        }
    }

    // ── OAuth Callback ─────────────────────────────────────────────────────

    /**
     * Google OAuth callback — exchanges code for tokens, verifies scopes, saves connection.
     *
     * GET /api/v1/integrations/google/callback?code=XXX&state=YYY
     */
    @GetMapping("/callback")
    @Transactional
    public void oauthCallback(
            @RequestParam String code,
            @RequestParam(required = false) String state,
            HttpServletResponse response) throws IOException {

        if (state == null || state.isBlank()) {
            response.sendRedirect(frontendUrl + "/settings/google-calendar?error=missing_state");
            return;
        }

        // Atomically fetch-and-delete the state (prevents replay attacks)
        RBucket<OAuthStatePayload> bucket = redissonClient.getBucket("oauth:state:" + state);
        OAuthStatePayload statePayload = bucket.getAndDelete();

        if (statePayload == null || statePayload.isExpired()) {
            log.warn("[IntegrationController] Invalid or expired OAuth state received");
            response.sendRedirect(frontendUrl + "/settings/google-calendar?error=invalid_state");
            return;
        }

        UUID userId = statePayload.getUserId();
        GoogleIntegrationType integration = statePayload.getIntegration();

        try {
            // Exchange authorization code for tokens
            String scope = integration.getOauthScope();
            GoogleAuthorizationCodeFlow flow = buildFlow(scope);
            TokenResponse tokenResponse = flow.newTokenRequest(code)
                    .setRedirectUri(googleConfig.getRedirectUri())
                    .execute();

            // Verify what scopes Google actually granted (granular permissions)
            String grantedScopes = (String) tokenResponse.get("scope");
            boolean requiredScopeGranted = grantedScopes != null
                    && grantedScopes.contains(integration.getOauthScope());

            // Save/update GoogleConnection (encrypted tokens)
            GoogleConnection conn = connectionRepository.findActiveByUserId(userId)
                    .orElse(new GoogleConnection());

            conn.setUserId(userId);
            userRepository.findById(userId).ifPresent(u -> {
                if (u.getTenant() != null) {
                    conn.setTenantId(u.getTenant().getId());
                }
            });
            conn.setAccessTokenEncrypted(
                    encryptionConverter.convertToDatabaseColumn(tokenResponse.getAccessToken()));

            if (tokenResponse.getRefreshToken() != null) {
                conn.setRefreshTokenEncrypted(
                        encryptionConverter.convertToDatabaseColumn(tokenResponse.getRefreshToken()));
            }

            long expiresIn = tokenResponse.getExpiresInSeconds() != null
                    ? tokenResponse.getExpiresInSeconds() : 3600L;
            conn.setExpiresAt(LocalDateTime.now().plusSeconds(expiresIn));
            conn.setGrantedScopes(grantedScopes);
            connectionRepository.save(conn);

            // Set integration status based on scope verification
            GoogleIntegrationStatus status = requiredScopeGranted
                    ? GoogleIntegrationStatus.CONNECTED
                    : GoogleIntegrationStatus.PARTIAL;

            upsertIntegrationStatus(userId, conn.getId(), integration, status);

            log.info("[IntegrationController] OAuth callback success userId={} integration={} status={}",
                    userId, integration, status);

            String featureTab = "google-" + integration.name().toLowerCase();
            String redirectParam = "?connected=" + integration.name().toLowerCase()
                    + "&status=" + status.name().toLowerCase();
            response.sendRedirect(frontendUrl + "/settings/" + featureTab + redirectParam);

        } catch (Exception e) {
            log.error("[IntegrationController] Callback failed for userId={} integration={}", userId, integration, e);
            String errorMsg = URLEncoder.encode(e.getMessage() != null ? e.getMessage() : "unknown_error",
                    StandardCharsets.UTF_8);
            String featureTab = (integration != null) ? ("google-" + integration.name().toLowerCase()) : "google-calendar";
            response.sendRedirect(frontendUrl + "/settings/" + featureTab + "?error=" + errorMsg);
        }
    }

    // ── Status ─────────────────────────────────────────────────────────────

    /**
     * Returns the current status of all Google integrations for the authenticated user.
     *
     * GET /api/v1/integrations/google/status
     * Response: { "CALENDAR": "CONNECTED", "GMAIL": "DISCONNECTED", ... }
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, String>> getStatus() {
        User user = getAuthenticatedUser();
        return ResponseEntity.ok(buildStatusMap(user));
    }

    private Map<String, String> buildStatusMap(User user) {
        // Initialize all integrations as DISCONNECTED
        Map<String, String> statusMap = Arrays.stream(GoogleIntegrationType.values())
                .collect(Collectors.toMap(Enum::name, t -> GoogleIntegrationStatus.DISCONNECTED.name()));

        // Overlay with actual stored statuses
        integrationRepository.findAllActiveByUserId(user.getId()).forEach(integration ->
                statusMap.put(integration.getFeature().name(), integration.getStatus().name()));

        return statusMap;
    }

    // ── Disconnect ─────────────────────────────────────────────────────────

    /**
     * Disconnects a specific Google integration feature.
     * - Marks integration as DISCONNECTED
     * - Best-effort: revokes Google token via Google's revocation endpoint
     * - If all features disconnected: clears encrypted tokens, sets revokedAt
     * - Preserves GoogleSync history (marks as DISCONNECTED, not deleted)
     *
     * DELETE /api/v1/integrations/google/{feature}
     */
    @DeleteMapping("/{feature}")
    @Transactional
    public ResponseEntity<Map<String, Object>> disconnect(@PathVariable String feature) {
        User user = getAuthenticatedUser();

        GoogleIntegrationType integrationType;
        try {
            integrationType = GoogleIntegrationType.valueOf(feature.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Unknown integration: " + feature));
        }

        Optional<GoogleConnection> connOpt = connectionRepository.findActiveByUserId(user.getId());
        if (connOpt.isEmpty()) {
            return ResponseEntity.ok(Map.of("disconnected", true, "message", "No active connection found"));
        }

        GoogleConnection conn = connOpt.get();

        // 1. Mark integration as DISCONNECTED
        integrationRepository.findByConnectionIdAndFeature(conn.getId(), integrationType)
                .ifPresent(integration -> {
                    integration.setStatus(GoogleIntegrationStatus.DISCONNECTED);
                    integration.setDisconnectedAt(LocalDateTime.now());
                    integrationRepository.save(integration);
                });

        // 2. Best-effort: call Google's token revocation endpoint
        try {
            String refreshToken = encryptionConverter.convertToEntityAttribute(conn.getRefreshTokenEncrypted());
            if (refreshToken != null && !refreshToken.isBlank()) {
                NetHttpTransport transport = GoogleNetHttpTransport.newTrustedTransport();
                transport.createRequestFactory()
                        .buildPostRequest(
                                new GenericUrl("https://oauth2.googleapis.com/revoke?token=" + refreshToken),
                                null)
                        .execute();
                log.info("[IntegrationController] Google token revoked for userId={}", user.getId());
            }
        } catch (Exception e) {
            log.warn("[IntegrationController] Token revocation failed (continuing with local disconnect): {}", e.getMessage());
        }

        // 3. Mark GoogleSync rows as DISCONNECTED (preserve history — don't delete)
        syncRepository.markDisconnectedByConnectionAndType(conn.getId(), integrationType.name());

        // 4. If ALL integrations disconnected, clear credentials entirely
        long remainingActive = integrationRepository.countActiveByConnectionId(conn.getId());
        if (remainingActive == 0) {
            conn.setRevokedAt(LocalDateTime.now());
            conn.setAccessTokenEncrypted(null);
            conn.setRefreshTokenEncrypted(null);
            connectionRepository.save(conn);
            log.info("[IntegrationController] All integrations disconnected — cleared credentials for userId={}", user.getId());
        }

        log.info("[IntegrationController] Disconnected {} for userId={}", integrationType, user.getId());
        return ResponseEntity.ok(Map.of("disconnected", true, "feature", integrationType.name()));
    }

    // ── Gmail Integration Endpoints ────────────────────────────────────────

    /**
     * Returns the Gmail integration status for the authenticated user.
     * GET /api/v1/integrations/google/gmail/status
     */
    @GetMapping("/gmail/status")
    public ResponseEntity<Map<String, Object>> getGmailStatus() {
        User user = getAuthenticatedUser();
        boolean connected = gmailService.isConnected(user.getId());
        return ResponseEntity.ok(Map.of(
                "connected", connected,
                "email", user.getEmail(),
                "feature", "GMAIL"
        ));
    }

    /**
     * Sends an email via the user's connected Gmail account.
     * POST /api/v1/integrations/google/gmail/send
     */
    @PostMapping("/gmail/send")
    public ResponseEntity<Map<String, Object>> sendGmail(@RequestBody SendGmailRequest request) {
        User user = getAuthenticatedUser();

        if (request.getTo() == null || request.getTo().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Recipient email ('to') is required"));
        }
        if (request.getSubject() == null || request.getSubject().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Email 'subject' is required"));
        }
        if (request.getBodyHtml() == null || request.getBodyHtml().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Email 'bodyHtml' is required"));
        }

        try {
            Map<String, Object> result = gmailService.sendEmail(
                    user,
                    request.getTo(),
                    request.getSubject(),
                    request.getBodyHtml(),
                    request.getCc(),
                    request.getBcc(),
                    request.getCrmResourceId()
            );
            return ResponseEntity.ok(result);

        } catch (IllegalStateException e) {
            log.warn("[IntegrationController] Gmail state error for userId={}: {}", user.getId(), e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("[IntegrationController] Failed to send Gmail for userId={}", user.getId(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to send email: " + e.getMessage()));
        }
    }

    public static class SendGmailRequest {
        private String to;
        private String subject;
        private String bodyHtml;
        private String cc;
        private String bcc;
        private UUID crmResourceId;

        public String getTo() { return to; }
        public void setTo(String to) { this.to = to; }
        public String getSubject() { return subject; }
        public void setSubject(String subject) { this.subject = subject; }
        public String getBodyHtml() { return bodyHtml; }
        public void setBodyHtml(String bodyHtml) { this.bodyHtml = bodyHtml; }
        public String getCc() { return cc; }
        public void setCc(String cc) { this.cc = cc; }
        public String getBcc() { return bcc; }
        public void setBcc(String bcc) { this.bcc = bcc; }
        public UUID getCrmResourceId() { return crmResourceId; }
        public void setCrmResourceId(UUID crmResourceId) { this.crmResourceId = crmResourceId; }
    }

    // ── Google Contacts Endpoints (Phase 8) ────────────────────────────────

    /**
     * Checks Google Contacts integration status.
     * GET /api/v1/integrations/google/contacts/status
     */
    @GetMapping("/contacts/status")
    public ResponseEntity<Map<String, Object>> getContactsStatus() {
        User user = getAuthenticatedUser();
        boolean connected = googleContactsService.isConnected(user.getId());
        return ResponseEntity.ok(Map.of(
                "connected", connected,
                "feature", "CONTACTS"
        ));
    }

    /**
     * Imports contacts from Google People API into CRM contacts.
     * POST /api/v1/integrations/google/contacts/import?maxResults=100
     */
    @PostMapping("/contacts/import")
    public ResponseEntity<Map<String, Object>> importContacts(
            @RequestParam(defaultValue = "100") int maxResults) {
        User user = getAuthenticatedUser();
        try {
            Map<String, Object> stats = googleContactsService.importContacts(user, maxResults);
            return ResponseEntity.ok(stats);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("[IntegrationController] Contacts import failed for userId={}", user.getId(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Contacts import failed: " + e.getMessage()));
        }
    }

    // ── Google Tasks Endpoints (Phase 10) ──────────────────────────────────

    /**
     * Checks Google Tasks integration status.
     * GET /api/v1/integrations/google/tasks/status
     */
    @GetMapping("/tasks/status")
    public ResponseEntity<Map<String, Object>> getTasksStatus() {
        User user = getAuthenticatedUser();
        boolean connected = googleTasksService.isConnected(user.getId());
        return ResponseEntity.ok(Map.of(
                "connected", connected,
                "feature", "TASKS"
        ));
    }

    /**
     * Creates a task in Google Tasks.
     * POST /api/v1/integrations/google/tasks/create
     */
    @PostMapping("/tasks/create")
    public ResponseEntity<Map<String, Object>> createTask(@RequestBody CreateTaskRequest request) {
        User user = getAuthenticatedUser();

        if (request.getTitle() == null || request.getTitle().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Task title is required"));
        }

        try {
            Map<String, Object> result = googleTasksService.createTask(
                    user,
                    request.getTitle(),
                    request.getNotes(),
                    request.getDueDateTime(),
                    request.getCrmResourceId()
            );
            return ResponseEntity.ok(result);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("[IntegrationController] Task creation failed for userId={}", user.getId(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Task creation failed: " + e.getMessage()));
        }
    }

    /**
     * Lists tasks from user's Google Tasks.
     * GET /api/v1/integrations/google/tasks/list?maxResults=50
     */
    @GetMapping("/tasks/list")
    public ResponseEntity<List<Map<String, Object>>> listTasks(
            @RequestParam(defaultValue = "50") int maxResults) {
        User user = getAuthenticatedUser();
        try {
            List<Map<String, Object>> tasks = googleTasksService.listTasks(user, maxResults);
            return ResponseEntity.ok(tasks);
        } catch (Exception e) {
            log.error("[IntegrationController] Listing tasks failed for userId={}", user.getId(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Marks a task as completed in Google Tasks.
     * POST /api/v1/integrations/google/tasks/{taskId}/complete
     */
    @PostMapping("/tasks/{taskId}/complete")
    public ResponseEntity<Map<String, Object>> completeTask(@PathVariable String taskId) {
        User user = getAuthenticatedUser();
        try {
            Map<String, Object> res = googleTasksService.completeTask(user, taskId);
            return ResponseEntity.ok(res);
        } catch (Exception e) {
            log.error("[IntegrationController] Completing task failed for userId={} taskId={}", user.getId(), taskId, e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    public static class CreateTaskRequest {
        private String title;
        private String notes;
        private LocalDateTime dueDateTime;
        private UUID crmResourceId;

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getNotes() { return notes; }
        public void setNotes(String notes) { this.notes = notes; }
        public LocalDateTime getDueDateTime() { return dueDateTime; }
        public void setDueDateTime(LocalDateTime dueDateTime) { this.dueDateTime = dueDateTime; }
        public UUID getCrmResourceId() { return crmResourceId; }
        public void setCrmResourceId(UUID crmResourceId) { this.crmResourceId = crmResourceId; }
    }

    // ── Google Drive Endpoints (Phase 11) ──────────────────────────────────

    /**
     * Checks Google Drive integration status.
     * GET /api/v1/integrations/google/drive/status
     */
    @GetMapping("/drive/status")
    public ResponseEntity<Map<String, Object>> getDriveStatus() {
        User user = getAuthenticatedUser();
        boolean connected = googleDriveService.isConnected(user.getId());
        return ResponseEntity.ok(Map.of(
                "connected", connected,
                "feature", "DRIVE"
        ));
    }

    /**
     * Uploads a document to Google Drive.
     * POST /api/v1/integrations/google/drive/upload
     */
    @PostMapping(value = "/drive/upload", consumes = "multipart/form-data")
    public ResponseEntity<Map<String, Object>> uploadToDrive(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "folderName", required = false) String folderName,
            @RequestParam(value = "crmResourceId", required = false) UUID crmResourceId) {
        User user = getAuthenticatedUser();
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "File payload cannot be empty"));
        }

        try {
            Map<String, Object> result = googleDriveService.uploadFile(
                    user,
                    file.getOriginalFilename(),
                    file.getContentType(),
                    file.getBytes(),
                    folderName,
                    crmResourceId
            );
            return ResponseEntity.ok(result);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("[IntegrationController] Drive upload failed for userId={}", user.getId(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Drive upload failed: " + e.getMessage()));
        }
    }

    /**
     * Lists files created by CRMLite in Google Drive.
     * GET /api/v1/integrations/google/drive/files
     */
    @GetMapping("/drive/files")
    public ResponseEntity<List<Map<String, Object>>> listDriveFiles(
            @RequestParam(defaultValue = "30") int maxResults) {
        User user = getAuthenticatedUser();
        try {
            List<Map<String, Object>> files = googleDriveService.listFiles(user, maxResults);
            return ResponseEntity.ok(files);
        } catch (Exception e) {
            log.error("[IntegrationController] Listing drive files failed for userId={}", user.getId(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ── Google Sheets Endpoints (Phase 12) ─────────────────────────────────

    /**
     * Checks Google Sheets integration status.
     * GET /api/v1/integrations/google/sheets/status
     */
    @GetMapping("/sheets/status")
    public ResponseEntity<Map<String, Object>> getSheetsStatus() {
        User user = getAuthenticatedUser();
        boolean connected = googleSheetsService.isConnected(user.getId());
        return ResponseEntity.ok(Map.of(
                "connected", connected,
                "feature", "SHEETS"
        ));
    }

    /**
     * Exports active CRM leads into a new Google Spreadsheet.
     * POST /api/v1/integrations/google/sheets/export
     */
    @PostMapping("/sheets/export")
    public ResponseEntity<Map<String, Object>> exportLeadsToSheet(
            @RequestBody(required = false) Map<String, String> body) {
        User user = getAuthenticatedUser();
        String title = (body != null) ? body.get("title") : null;
        try {
            Map<String, Object> result = googleSheetsService.exportLeadsToSheet(user, title);
            return ResponseEntity.ok(result);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("[IntegrationController] Sheets export failed for userId={}", user.getId(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Sheets export failed: " + e.getMessage()));
        }
    }

    /**
     * Imports leads from an existing Google Spreadsheet.
     * POST /api/v1/integrations/google/sheets/import
     */
    @PostMapping("/sheets/import")
    public ResponseEntity<Map<String, Object>> importLeadsFromSheet(
            @RequestBody Map<String, String> body) {
        User user = getAuthenticatedUser();
        String spreadsheetId = body != null ? body.get("spreadsheetId") : null;
        String range = body != null ? body.get("range") : null;

        if (spreadsheetId == null || spreadsheetId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "spreadsheetId is required"));
        }

        try {
            Map<String, Object> result = googleSheetsService.importLeadsFromSheet(user, spreadsheetId, range);
            return ResponseEntity.ok(result);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("[IntegrationController] Sheets import failed for userId={}", user.getId(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Sheets import failed: " + e.getMessage()));
        }
    }

    // ── Private Helpers ────────────────────────────────────────────────────

    private GoogleAuthorizationCodeFlow buildFlow(String scope) throws Exception {
        GoogleClientSecrets.Details details = new GoogleClientSecrets.Details()
                .setClientId(googleConfig.getClientId())
                .setClientSecret(googleConfig.getClientSecret());
        GoogleClientSecrets clientSecrets = new GoogleClientSecrets().setInstalled(details);

        return new GoogleAuthorizationCodeFlow.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                JSON_FACTORY,
                clientSecrets,
                List.of(scope))
                .setAccessType("offline")
                .setDataStoreFactory(new MemoryDataStoreFactory())
                .build();
    }

    /** Upsert GoogleIntegration status by userId (before connection exists — PENDING state). */
    private void upsertIntegrationStatus(UUID userId, GoogleIntegrationType feature, GoogleIntegrationStatus status) {
        connectionRepository.findActiveByUserId(userId).ifPresent(conn ->
                upsertIntegrationStatus(userId, conn.getId(), feature, status));
    }

    /** Upsert GoogleIntegration status by connectionId. */
    private void upsertIntegrationStatus(UUID userId, UUID connectionId,
                                          GoogleIntegrationType feature, GoogleIntegrationStatus status) {
        GoogleIntegration integration = integrationRepository
                .findByConnectionIdAndFeature(connectionId, feature)
                .orElse(new GoogleIntegration());

        integration.setConnectionId(connectionId);
        integration.setFeature(feature);
        integration.setStatus(status);

        if (status == GoogleIntegrationStatus.CONNECTED && integration.getConnectedAt() == null) {
            integration.setConnectedAt(LocalDateTime.now());
        }
        if (status == GoogleIntegrationStatus.DISCONNECTED) {
            integration.setDisconnectedAt(LocalDateTime.now());
        }

        integrationRepository.save(integration);
    }

    @Autowired(required = false)
    private com.chatcrmlite.backend.repositories.GoogleApiAuditLogRepository auditLogRepository;

    @Autowired(required = false)
    private com.chatcrmlite.backend.repositories.GoogleDeadLetterRepository deadLetterRepository;

    /**
     * Returns recent audit logs for the authenticated user's Google API calls.
     */
    @GetMapping("/audit-logs")
    public ResponseEntity<?> getAuditLogs() {
        User user = getAuthenticatedUser();
        if (auditLogRepository == null) {
            return ResponseEntity.ok(Collections.emptyList());
        }
        List<com.chatcrmlite.backend.models.google.GoogleApiAuditLog> logs =
                auditLogRepository.findTop50ByUserIdOrderByCreatedAtDesc(user.getId());
        return ResponseEntity.ok(logs);
    }

    /**
     * Returns unresolved dead letters (failed async sync tasks) for inspection.
     */
    @GetMapping("/dead-letters")
    public ResponseEntity<?> getDeadLetters() {
        User user = getAuthenticatedUser();
        if (deadLetterRepository == null) {
            return ResponseEntity.ok(Collections.emptyList());
        }
        List<com.chatcrmlite.backend.models.google.GoogleDeadLetter> deadLetters =
                deadLetterRepository.findByUserIdAndResolvedFalseOrderByCreatedAtDesc(user.getId());
        return ResponseEntity.ok(deadLetters);
    }

    /**
     * Resolves a dead letter task.
     */
    @PostMapping("/dead-letters/{id}/resolve")
    public ResponseEntity<?> resolveDeadLetter(@PathVariable UUID id) {
        User user = getAuthenticatedUser();
        if (deadLetterRepository == null) {
            return ResponseEntity.ok(Map.of("success", true));
        }
        deadLetterRepository.findById(id).ifPresent(dl -> {
            if (user.getId().equals(dl.getUserId())) {
                dl.setResolved(true);
                dl.setResolvedAt(LocalDateTime.now());
                deadLetterRepository.save(dl);
            }
        });
        return ResponseEntity.ok(Map.of("success", true, "resolvedId", id));
    }

    /**
     * Returns Google Workspace integration health metrics and status summary.
     */
    @GetMapping("/health")
    public ResponseEntity<?> getIntegrationHealth() {
        User user = getAuthenticatedUser();
        Optional<GoogleConnection> connOpt = connectionRepository.findActiveByUserId(user.getId());

        Map<String, Object> health = new HashMap<>();
        health.put("userId", user.getId());
        health.put("hasActiveConnection", connOpt.isPresent());

        if (connOpt.isPresent()) {
            GoogleConnection conn = connOpt.get();
            health.put("connectionId", conn.getId());
            health.put("googleSubjectId", conn.getGoogleSubjectId());
            health.put("tokenExpired", conn.isAccessTokenExpired());
            health.put("grantedScopes", conn.getGrantedScopes() != null ? conn.getGrantedScopes().split(" ") : new String[0]);
            health.put("connectedAt", conn.getCreatedAt());
        }

        // Audit & Error metrics
        LocalDateTime since24h = LocalDateTime.now().minusHours(24);
        if (auditLogRepository != null) {
            long totalCalls = auditLogRepository.countByUserIdAndCreatedAtAfter(user.getId(), since24h);
            long failedCalls = auditLogRepository.countByUserIdAndStatusAndCreatedAtAfter(user.getId(), "FAILED", since24h);
            health.put("apiCalls24h", totalCalls);
            health.put("apiFailures24h", failedCalls);
            health.put("healthRate", totalCalls > 0 ? (double)(totalCalls - failedCalls) / totalCalls * 100.0 : 100.0);
        } else {
            health.put("apiCalls24h", 0);
            health.put("apiFailures24h", 0);
            health.put("healthRate", 100.0);
        }

        if (deadLetterRepository != null) {
            health.put("unresolvedDeadLetters", deadLetterRepository.countByUserIdAndResolvedFalse(user.getId()));
        } else {
            health.put("unresolvedDeadLetters", 0);
        }

        // Integration feature statuses
        health.put("features", buildStatusMap(user));

        return ResponseEntity.ok(health);
    }
}

