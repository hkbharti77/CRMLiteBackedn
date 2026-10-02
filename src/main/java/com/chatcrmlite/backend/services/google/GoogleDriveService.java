package com.chatcrmlite.backend.services.google;

import com.chatcrmlite.backend.config.GoogleConfig;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.google.*;
import com.chatcrmlite.backend.repositories.GoogleConnectionRepository;
import com.chatcrmlite.backend.repositories.GoogleIntegrationRepository;
import com.chatcrmlite.backend.repositories.GoogleSyncRepository;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Service for Google Drive integration (drive.file scope).
 *
 * Provides document uploads, agreement archiving, and client folder management.
 * Scope 'drive.file' restricts access only to files created by CRMLite or opened with it.
 */
@Service
public class GoogleDriveService {

    private static final Logger log = LoggerFactory.getLogger(GoogleDriveService.class);
    private static final String APPLICATION_NAME = "CRMLite";
    private static final GsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private static final String DEFAULT_ROOT_FOLDER = "GyanVaniAi CRM Documents";

    @Autowired private GoogleTokenService googleTokenService;
    @Autowired private GoogleConnectionRepository connectionRepository;
    @Autowired private GoogleIntegrationRepository integrationRepository;
    @Autowired private GoogleSyncRepository syncRepository;
    @Autowired(required = false) private GoogleAuditService auditService;

    /**
     * Checks if the user has an active Google Drive integration.
     */
    public boolean isConnected(UUID userId) {
        if (userId == null) return false;

        Optional<GoogleConnection> connOpt = connectionRepository.findActiveByUserId(userId);
        if (connOpt.isEmpty()) return false;

        GoogleConnection conn = connOpt.get();
        Optional<GoogleIntegration> integrationOpt =
                integrationRepository.findByConnectionIdAndFeature(conn.getId(), GoogleIntegrationType.DRIVE);

        if (integrationOpt.isPresent() && integrationOpt.get().getStatus() == GoogleIntegrationStatus.CONNECTED) {
            return true;
        }

        return conn.hasScope("drive.file") || conn.hasScope("https://www.googleapis.com/auth/drive.file");
    }

    /**
     * Uploads a file to the user's Google Drive.
     *
     * @param user The authenticated user
     * @param fileName Original file name
     * @param mimeType MIME type of the file
     * @param fileBytes File payload
     * @param folderName Optional folder name (defaults to "GyanVaniAi CRM Documents")
     * @param crmResourceId Optional associated CRM entity ID (lead, agreement, invoice)
     * @return Map containing fileId, name, webViewLink, webContentLink, size
     */
    @Transactional
    public Map<String, Object> uploadFile(
            User user,
            String fileName,
            String mimeType,
            byte[] fileBytes,
            String folderName,
            UUID crmResourceId) throws GeneralSecurityException, IOException {

        if (user == null || user.getId() == null) {
            throw new IllegalArgumentException("User must not be null");
        }

        GoogleConnection conn = connectionRepository.findActiveByUserId(user.getId())
                .orElseThrow(() -> new IllegalStateException("No active Google connection found. Please connect Google Drive in Settings."));

        if (!isConnected(user.getId())) {
            throw new IllegalStateException("Google Drive is not connected. Please authorize Drive access in Settings.");
        }

        String accessToken = googleTokenService.getValidAccessToken(conn.getId());
        Drive driveClient = buildDriveClient(accessToken);

        // Find or create parent folder
        String targetFolderName = (folderName != null && !folderName.isBlank()) ? folderName : DEFAULT_ROOT_FOLDER;
        String folderId = findOrCreateFolder(driveClient, targetFolderName);

        File fileMetadata = new File();
        fileMetadata.setName(fileName);
        fileMetadata.setParents(Collections.singletonList(folderId));

        ByteArrayContent mediaContent = new ByteArrayContent(
                (mimeType != null && !mimeType.isBlank()) ? mimeType : "application/octet-stream",
                fileBytes
        );

        long startTime = System.currentTimeMillis();
        File uploadedFile;
        try {
            uploadedFile = driveClient.files().create(fileMetadata, mediaContent)
                    .setFields("id, name, mimeType, webViewLink, webContentLink, size, createdTime")
                    .execute();
            if (auditService != null) {
                auditService.logSuccess(user.getId(), "DRIVE", "UPLOAD_FILE", "drive.googleapis.com", System.currentTimeMillis() - startTime);
            }
        } catch (Exception ex) {
            if (auditService != null) {
                auditService.logFailure(user.getId(), "DRIVE", "UPLOAD_FILE", "drive.googleapis.com", System.currentTimeMillis() - startTime, ex, 0);
            }
            throw ex;
        }

        log.info("[GoogleDriveService] Uploaded file '{}' (id: {}) for userId={}", fileName, uploadedFile.getId(), user.getId());

        // Save GoogleSync mapping
        if (crmResourceId != null) {
            try {
                GoogleSync sync = new GoogleSync();
                sync.setConnectionId(conn.getId());
                sync.setResourceType("DRIVE_FILE");
                sync.setCrmResourceId(crmResourceId);
                sync.setGoogleResourceId(uploadedFile.getId());
                sync.setSyncDirection("CRM_TO_GOOGLE");
                sync.setSyncStatus("OK");
                sync.setLastSyncedAt(LocalDateTime.now());
                syncRepository.save(sync);
            } catch (Exception e) {
                log.warn("[GoogleDriveService] Failed to record GoogleSync mapping: {}", e.getMessage());
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("fileId", uploadedFile.getId());
        result.put("fileName", uploadedFile.getName());
        result.put("webViewLink", uploadedFile.getWebViewLink());
        result.put("webContentLink", uploadedFile.getWebContentLink());
        result.put("size", uploadedFile.getSize());
        result.put("mimeType", uploadedFile.getMimeType());
        result.put("createdTime", uploadedFile.getCreatedTime() != null ? uploadedFile.getCreatedTime().toString() : null);
        return result;
    }

    /**
     * Lists files created by this application in Google Drive.
     */
    public List<Map<String, Object>> listFiles(User user, int maxResults) throws GeneralSecurityException, IOException {
        if (!isConnected(user.getId())) {
            return Collections.emptyList();
        }

        GoogleConnection conn = connectionRepository.findActiveByUserId(user.getId()).orElse(null);
        if (conn == null) return Collections.emptyList();

        String accessToken = googleTokenService.getValidAccessToken(conn.getId());
        Drive driveClient = buildDriveClient(accessToken);

        FileList fileList = driveClient.files().list()
                .setPageSize(Math.min(maxResults, 50))
                .setQ("trashed = false and mimeType != 'application/vnd.google-apps.folder'")
                .setFields("files(id, name, mimeType, webViewLink, webContentLink, size, createdTime)")
                .execute();

        List<File> files = fileList.getFiles();
        if (files == null || files.isEmpty()) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (File f : files) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", f.getId());
            map.put("name", f.getName());
            map.put("mimeType", f.getMimeType());
            map.put("webViewLink", f.getWebViewLink());
            map.put("webContentLink", f.getWebContentLink());
            map.put("size", f.getSize());
            map.put("createdTime", f.getCreatedTime() != null ? f.getCreatedTime().toString() : null);
            result.add(map);
        }
        return result;
    }

    /**
     * Creates a folder in Google Drive.
     */
    public String createFolder(User user, String folderName) throws GeneralSecurityException, IOException {
        GoogleConnection conn = connectionRepository.findActiveByUserId(user.getId())
                .orElseThrow(() -> new IllegalStateException("No active Google connection found."));

        String accessToken = googleTokenService.getValidAccessToken(conn.getId());
        Drive driveClient = buildDriveClient(accessToken);
        return findOrCreateFolder(driveClient, folderName);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private String findOrCreateFolder(Drive driveClient, String folderName) throws IOException {
        // Check if folder exists
        FileList existing = driveClient.files().list()
                .setQ("mimeType = 'application/vnd.google-apps.folder' and name = '" + folderName.replace("'", "\\'") + "' and trashed = false")
                .setFields("files(id, name)")
                .execute();

        if (existing.getFiles() != null && !existing.getFiles().isEmpty()) {
            return existing.getFiles().get(0).getId();
        }

        // Create folder
        File folderMetadata = new File();
        folderMetadata.setName(folderName);
        folderMetadata.setMimeType("application/vnd.google-apps.folder");

        File createdFolder = driveClient.files().create(folderMetadata)
                .setFields("id")
                .execute();

        log.info("[GoogleDriveService] Created Drive folder '{}' (id: {})", folderName, createdFolder.getId());
        return createdFolder.getId();
    }

    private Drive buildDriveClient(String accessToken) throws GeneralSecurityException, IOException {
        HttpTransport transport = GoogleNetHttpTransport.newTrustedTransport();
        HttpRequestInitializer initializer = request ->
                request.getHeaders().setAuthorization("Bearer " + accessToken);

        return new Drive.Builder(transport, JSON_FACTORY, initializer)
                .setApplicationName(APPLICATION_NAME)
                .build();
    }
}
