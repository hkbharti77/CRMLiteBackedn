package com.chatcrmlite.backend.services.storage;

import com.cloudinary.Cloudinary;
import com.cloudinary.Transformation;
import com.cloudinary.utils.ObjectUtils;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class CloudinaryStorageService {

    @Value("${cloudinary.url:}")
    private String cloudinaryUrl;

    private Cloudinary cloudinary;

    @PostConstruct
    public void init() {
        String activeUrl = cloudinaryUrl;
        if (activeUrl == null || activeUrl.isBlank()) {
            activeUrl = System.getenv("CLOUDINARY_URL");
        }

        if (activeUrl != null && !activeUrl.isBlank()) {
            try {
                this.cloudinary = new Cloudinary(activeUrl);
                this.cloudinary.config.secure = true;
                log.info("Initialized Cloudinary Client for cloud: {}", cloudinary.config.cloudName);
            } catch (Exception e) {
                log.error("Failed to initialize Cloudinary Client: {}", e.getMessage(), e);
            }
        } else {
            log.warn("Cloudinary URL not provided. CloudinaryStorageService running in unconfigured mode.");
        }
    }

    public boolean isConfigured() {
        return cloudinary != null;
    }

    /**
     * Builds a standardized tenant-isolated storage key/path.
     * Example: "tenants/3fa85f64-5717-4562-b3fc-2c963f66afa6/catalog/1723659483921_service.png"
     */
    public String buildTenantKey(java.util.UUID tenantId, String category, String filename) {
        String safeTenant = (tenantId != null) ? tenantId.toString() : "default";
        return buildTenantKey(safeTenant, category, filename);
    }

    public String buildTenantKey(String tenantIdStr, String category, String filename) {
        String safeTenant = (tenantIdStr != null && !tenantIdStr.isBlank()) ? tenantIdStr.trim() : "default";
        String safeCategory = (category != null && !category.isBlank()) ? category.trim().replaceAll("^/+|/+$", "") : "uploads";
        String sanitized = (filename != null && !filename.isBlank()) 
                ? filename.replaceAll("[^a-zA-Z0-9._-]", "_") 
                : "file_" + System.currentTimeMillis();
        return "tenants/" + safeTenant + "/" + safeCategory + "/" + System.currentTimeMillis() + "_" + sanitized;
    }

    /**
     * Uploads a file scoped directly to a tenant's isolated Cloudinary folder.
     */
    public String uploadTenantFile(java.util.UUID tenantId, String category, MultipartFile file) throws Exception {
        String originalFilename = (file != null && file.getOriginalFilename() != null) ? file.getOriginalFilename() : "upload";
        String key = buildTenantKey(tenantId, category, originalFilename);
        return uploadFile(key, file);
    }

    /**
     * Uploads a file (Image, Video, Audio, Document) to Cloudinary and returns the optimized secure HTTPS CDN URL.
     */
    public String uploadFile(String key, MultipartFile file) throws Exception {
        if (!isConfigured()) {
            throw new IllegalStateException("Cloudinary client is not configured.");
        }

        Map<String, Object> params = new HashMap<>();
        params.put("resource_type", "auto");
        params.put("overwrite", true);

        String publicId = key;
        if (key != null && !key.isBlank()) {
            int lastSlash = key.lastIndexOf('/');
            if (lastSlash != -1) {
                params.put("folder", key.substring(0, lastSlash));
                publicId = key.substring(lastSlash + 1);
                params.put("public_id", publicId);
            } else {
                params.put("public_id", key);
            }
        }

        Map<?, ?> uploadResult = cloudinary.uploader().upload(file.getBytes(), params);
        String secureUrl = (String) uploadResult.get("secure_url");
        if (secureUrl == null) {
            secureUrl = (String) uploadResult.get("url");
        }

        String resourceType = (String) uploadResult.get("resource_type");
        String finalPublicId = (String) uploadResult.get("public_id");

        // If it's an image, optimize with auto quality and auto format (WebP/AVIF), and log demonstration tag
        if ("image".equalsIgnoreCase(resourceType) && finalPublicId != null) {
            try {
                // Generate and log optimized transformation demonstration (e.g. pad 300x400 auto:predominant)
                Transformation demoTransformation = new Transformation()
                        .crop("pad")
                        .width(300)
                        .height(400)
                        .background("auto:predominant")
                        .quality("auto")
                        .fetchFormat("auto");

                String imageTag = cloudinary.url().secure(true).transformation(demoTransformation).imageTag(finalPublicId);
                log.info("🖼️ [Cloudinary Optimization] Transformed Image Tag Demo: {}", imageTag);

                // Generate optimized secure delivery URL with automatic format and compression
                secureUrl = optimizeUrl(secureUrl);
            } catch (Exception ex) {
                log.debug("Cloudinary transformation tag generation notice: {}", ex.getMessage());
            }
        }

        log.info("Successfully uploaded object to Cloudinary: {} -> {}", key, secureUrl);
        return secureUrl;
    }

    /**
     * Uploads raw byte array to Cloudinary with tenant-scoped folder and specified resource type.
     */
    public String uploadTenantBytes(java.util.UUID tenantId, String category, String filename, byte[] bytes, String resourceType) throws Exception {
        String key = buildTenantKey(tenantId, category, filename);
        return uploadBytes(key, bytes, resourceType);
    }

    /**
     * Uploads raw byte array to Cloudinary with specified resource type.
     */
    public String uploadBytes(String key, byte[] bytes, String resourceType) throws Exception {
        if (!isConfigured()) {
            throw new IllegalStateException("Cloudinary client is not configured.");
        }
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Byte array to upload cannot be empty.");
        }

        Map<String, Object> params = new HashMap<>();
        params.put("resource_type", (resourceType != null && !resourceType.isBlank()) ? resourceType : "auto");
        params.put("overwrite", true);

        String publicId = key;
        if (key != null && !key.isBlank()) {
            int lastSlash = key.lastIndexOf('/');
            if (lastSlash != -1) {
                params.put("folder", key.substring(0, lastSlash));
                publicId = key.substring(lastSlash + 1);
                params.put("public_id", publicId);
            } else {
                params.put("public_id", key);
            }
        }

        Map<?, ?> uploadResult = cloudinary.uploader().upload(bytes, params);
        String secureUrl = (String) uploadResult.get("secure_url");
        if (secureUrl == null) {
            secureUrl = (String) uploadResult.get("url");
        }

        String resType = (String) uploadResult.get("resource_type");
        if ("image".equalsIgnoreCase(resType)) {
            secureUrl = optimizeUrl(secureUrl);
        }

        log.info("Successfully uploaded byte array to Cloudinary: {} -> {} (size: {} bytes)", key, secureUrl, bytes.length);
        return secureUrl;
    }

    /**
     * Uploads an InputStream to Cloudinary with tenant-scoped folder and specified resource type.
     */
    public String uploadTenantStream(java.util.UUID tenantId, String category, String filename, InputStream inputStream, String resourceType) throws Exception {
        String key = buildTenantKey(tenantId, category, filename);
        return uploadStream(key, inputStream, resourceType);
    }

    /**
     * Uploads an InputStream to Cloudinary with specified resource type.
     */
    public String uploadStream(String key, InputStream inputStream, String resourceType) throws Exception {
        if (!isConfigured()) {
            throw new IllegalStateException("Cloudinary client is not configured.");
        }
        if (inputStream == null) {
            throw new IllegalArgumentException("InputStream to upload cannot be null.");
        }

        Map<String, Object> params = new HashMap<>();
        params.put("resource_type", (resourceType != null && !resourceType.isBlank()) ? resourceType : "auto");
        params.put("overwrite", true);

        String publicId = key;
        if (key != null && !key.isBlank()) {
            int lastSlash = key.lastIndexOf('/');
            if (lastSlash != -1) {
                params.put("folder", key.substring(0, lastSlash));
                publicId = key.substring(lastSlash + 1);
                params.put("public_id", publicId);
            } else {
                params.put("public_id", key);
            }
        }

        Map<?, ?> uploadResult = cloudinary.uploader().upload(inputStream, params);
        String secureUrl = (String) uploadResult.get("secure_url");
        if (secureUrl == null) {
            secureUrl = (String) uploadResult.get("url");
        }

        String resType = (String) uploadResult.get("resource_type");
        if ("image".equalsIgnoreCase(resType)) {
            secureUrl = optimizeUrl(secureUrl);
        }

        log.info("Successfully uploaded stream to Cloudinary: {} -> {}", key, secureUrl);
        return secureUrl;
    }

    /**
     * Creates a custom transformed URL and HTML image tag for a given public ID.
     */
    public String getTransformedImageUrl(String publicId, int width, int height, String crop, String background) {
        if (!isConfigured() || publicId == null || publicId.isBlank()) return "";
        try {
            Transformation transformation = new Transformation()
                    .crop(crop != null ? crop : "pad")
                    .width(width)
                    .height(height)
                    .background(background != null ? background : "auto:predominant")
                    .quality("auto")
                    .fetchFormat("auto");

            String transformedUrl = cloudinary.url().secure(true).transformation(transformation).generate(publicId);
            String imageTag = cloudinary.url().secure(true).transformation(transformation).imageTag(publicId);
            log.info("🖼️ [Cloudinary Transformation] Generated URL: {} | Tag: {}", transformedUrl, imageTag);
            return transformedUrl;
        } catch (Exception e) {
            log.warn("Failed to generate transformed image URL for {}: {}", publicId, e.getMessage());
            return getPublicUrl(publicId);
        }
    }

    /**
     * Injects auto-quality and auto-format delivery optimizations (f_auto,q_auto) into a Cloudinary URL.
     */
    public String optimizeUrl(String url) {
        if (url == null || !url.contains("cloudinary.com") || !url.contains("/upload/")) {
            return url;
        }
        if (url.contains("/upload/f_auto,q_auto/") || url.contains("/upload/q_auto,f_auto/")) {
            return url;
        }
        return url.replace("/upload/", "/upload/f_auto,q_auto/");
    }

    /**
     * Downloads file bytes from a Cloudinary URL or public key.
     */
    public byte[] downloadFile(String urlOrKey) throws Exception {
        if (urlOrKey == null || urlOrKey.isBlank()) {
            throw new IllegalArgumentException("File URL or key cannot be empty.");
        }

        String targetUrl = urlOrKey;
        if (!targetUrl.startsWith("http://") && !targetUrl.startsWith("https://")) {
            targetUrl = getPublicUrl(urlOrKey);
        }

        try (InputStream in = URI.create(targetUrl).toURL().openStream()) {
            return in.readAllBytes();
        }
    }

    /**
     * Deletes an asset from Cloudinary.
     */
    public void deleteFile(String publicIdOrUrl) {
        if (!isConfigured() || publicIdOrUrl == null) return;
        try {
            String publicId = publicIdOrUrl;
            if (publicId.startsWith("http://") || publicId.startsWith("https://")) {
                int uploadIdx = publicId.indexOf("/upload/");
                if (uploadIdx != -1) {
                    String sub = publicId.substring(uploadIdx + 8);
                    // Remove transformations if present e.g. f_auto,q_auto/
                    if (sub.startsWith("f_auto,q_auto/")) {
                        sub = sub.substring(14);
                    }
                    // Remove version prefix e.g. v12345678/
                    if (sub.matches("^v\\d+/.*")) {
                        sub = sub.substring(sub.indexOf('/') + 1);
                    }
                    // Remove file extension
                    int dotIdx = sub.lastIndexOf('.');
                    publicId = dotIdx != -1 ? sub.substring(0, dotIdx) : sub;
                }
            }

            cloudinary.uploader().destroy(publicId, ObjectUtils.emptyMap());
            log.info("Successfully deleted object from Cloudinary: {}", publicId);
        } catch (Exception e) {
            log.error("Failed to delete Cloudinary object {}: {}", publicIdOrUrl, e.getMessage());
        }
    }

    /**
     * Returns a public HTTPS URL for the given key or URL.
     */
    public String getPublicUrl(String keyOrUrl) {
        if (keyOrUrl == null || keyOrUrl.isBlank()) return "";
        if (keyOrUrl.startsWith("http://") || keyOrUrl.startsWith("https://")) {
            return optimizeUrl(keyOrUrl);
        }
        if (cloudinary != null) {
            try {
                Transformation transformation = new Transformation().quality("auto").fetchFormat("auto");
                return cloudinary.url().secure(true).transformation(transformation).generate(keyOrUrl);
            } catch (Exception e) {
                log.warn("Could not generate Cloudinary URL for key {}: {}", keyOrUrl, e.getMessage());
            }
        }
        return "https://res.cloudinary.com/" + getCloudName() + "/image/upload/f_auto,q_auto/" + keyOrUrl;
    }

    public String getCloudName() {
        return (cloudinary != null && cloudinary.config.cloudName != null) ? cloudinary.config.cloudName : "";
    }
}
