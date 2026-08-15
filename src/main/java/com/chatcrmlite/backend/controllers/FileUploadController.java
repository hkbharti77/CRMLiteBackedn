package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.storage.CloudinaryStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/upload")
@RequiredArgsConstructor
public class FileUploadController {

    private final CloudinaryStorageService cloudinaryStorageService;
    private final UserRepository userRepository;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "folder", defaultValue = "media") String folder,
            @AuthenticationPrincipal String email) {

        if (email == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Unauthorized"));
        }

        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "User not found"));
        }

        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "File is empty"));
        }

        // Limit file size to 50MB
        if (file.getSize() > 50L * 1024 * 1024) {
            return ResponseEntity.badRequest().body(Map.of("error", "File size exceeds 50MB limit"));
        }

        if (!cloudinaryStorageService.isConfigured()) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Cloudinary Storage is not configured on the server."));
        }

        try {
            String originalFilename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "file";
            String sanitizedFilename = originalFilename.replaceAll("[^a-zA-Z0-9._-]", "_");
            java.util.UUID tenantId = (user.getTenant() != null ? user.getTenant().getId() : user.getId());
            
            String key = cloudinaryStorageService.buildTenantKey(tenantId, folder, sanitizedFilename);
            String mediaUrl = cloudinaryStorageService.uploadFile(key, file);

            String contentType = file.getContentType() != null ? file.getContentType() : "application/octet-stream";
            String mediaType = resolveMediaType(contentType, sanitizedFilename);

            log.info("✅ [Cloudinary Upload] Tenant {} uploaded {} to {} (Size: {} bytes, Type: {})", tenantId, sanitizedFilename, mediaUrl, file.getSize(), mediaType);

            Map<String, Object> resp = new HashMap<>();
            resp.put("url", mediaUrl);
            resp.put("key", key);
            resp.put("filename", originalFilename);
            resp.put("contentType", contentType);
            resp.put("mediaType", mediaType);
            resp.put("size", file.getSize());

            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            log.error("❌ [Cloudinary Upload] Failed to upload file: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to upload file to Cloudinary: " + e.getMessage()));
        }
    }

    private String resolveMediaType(String contentType, String filename) {
        String lowerContent = contentType.toLowerCase();
        String lowerName = filename.toLowerCase();

        if (lowerContent.startsWith("image/") || lowerName.matches(".*\\.(png|jpg|jpeg|webp|gif|svg|bmp)$")) {
            return "IMAGE";
        }
        if (lowerContent.startsWith("video/") || lowerName.matches(".*\\.(mp4|webm|mov|avi|mkv|3gp|m4v)$")) {
            return "VIDEO";
        }
        if (lowerContent.startsWith("audio/") || lowerName.matches(".*\\.(mp3|wav|ogg|aac|m4a|flac)$")) {
            return "AUDIO";
        }
        return "DOCUMENT";
    }
}
