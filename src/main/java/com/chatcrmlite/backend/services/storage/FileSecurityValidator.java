package com.chatcrmlite.backend.services.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
public class FileSecurityValidator {

    private static final long MAX_DOCUMENT_SIZE = 25 * 1024 * 1024; // 25 MB
    private static final long MAX_IMAGE_SIZE = 10 * 1024 * 1024;     // 10 MB

    private static final Set<String> DANGEROUS_EXTENSIONS = Set.of(
            "exe", "bat", "cmd", "sh", "bin", "jar", "app", "msi", "com",
            "php", "py", "pl", "rb", "js", "vbs", "ps1", "jsp", "asp", "aspx"
    );

    private static final List<String> ALLOWED_MIME_TYPES = List.of(
            "application/pdf",
            "image/jpeg",
            "image/png",
            "image/webp"
    );

    // Magic Bytes (File Signatures)
    private static final byte[] PDF_HEADER = new byte[]{0x25, 0x50, 0x44, 0x46, 0x2D}; // %PDF-
    private static final byte[] PNG_HEADER = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] JPEG_PREFIX = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] RIFF_PREFIX = new byte[]{0x52, 0x49, 0x46, 0x46}; // RIFF (for WebP)
    private static final byte[] WEBP_SUFFIX = new byte[]{0x57, 0x45, 0x42, 0x50}; // WEBP

    public record ValidationResult(boolean valid, String detectedMimeType, String mediaType, String errorMessage) {}

    public ValidationResult validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return new ValidationResult(false, null, null, "File is missing or empty.");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            return new ValidationResult(false, null, null, "File name cannot be empty.");
        }

        // 1. Extension inspection
        String extension = getExtension(originalFilename).toLowerCase();
        if (DANGEROUS_EXTENSIONS.contains(extension)) {
            log.warn("Blocked dangerous file extension upload attempt: {}", originalFilename);
            return new ValidationResult(false, null, null, "Execution of dangerous file types is blocked.");
        }

        // 2. Read first 16 bytes for Magic Byte inspection
        byte[] header = new byte[16];
        try (InputStream is = file.getInputStream()) {
            int bytesRead = is.read(header);
            if (bytesRead < 4) {
                return new ValidationResult(false, null, null, "File header corrupted or too small.");
            }
        } catch (Exception e) {
            log.error("Failed to read file input stream: {}", e.getMessage());
            return new ValidationResult(false, null, null, "Failed to read file.");
        }

        // 3. Match Magic Bytes
        String detectedMime;
        String mediaType;

        if (startsWith(header, PDF_HEADER)) {
            detectedMime = "application/pdf";
            mediaType = "DOCUMENT";
            if (file.getSize() > MAX_DOCUMENT_SIZE) {
                return new ValidationResult(false, detectedMime, mediaType, "PDF file exceeds maximum allowed limit of 25MB.");
            }
        } else if (startsWith(header, PNG_HEADER)) {
            detectedMime = "image/png";
            mediaType = "IMAGE";
            if (file.getSize() > MAX_IMAGE_SIZE) {
                return new ValidationResult(false, detectedMime, mediaType, "PNG file exceeds maximum allowed limit of 10MB.");
            }
        } else if (startsWith(header, JPEG_PREFIX)) {
            detectedMime = "image/jpeg";
            mediaType = "IMAGE";
            if (file.getSize() > MAX_IMAGE_SIZE) {
                return new ValidationResult(false, detectedMime, mediaType, "JPEG file exceeds maximum allowed limit of 10MB.");
            }
        } else if (startsWith(header, RIFF_PREFIX) && containsAt(header, WEBP_SUFFIX, 8)) {
            detectedMime = "image/webp";
            mediaType = "IMAGE";
            if (file.getSize() > MAX_IMAGE_SIZE) {
                return new ValidationResult(false, detectedMime, mediaType, "WebP file exceeds maximum allowed limit of 10MB.");
            }
        } else {
            log.warn("File signature verification failed for: {}. Header bytes did not match allowed documents or images.", originalFilename);
            return new ValidationResult(false, null, null, "Invalid file content. Allowed formats: PDF, PNG, JPG, WEBP.");
        }

        return new ValidationResult(true, detectedMime, mediaType, null);
    }

    private String getExtension(String filename) {
        int dotIndex = filename.lastIndexOf('.');
        return (dotIndex != -1 && dotIndex < filename.length() - 1) ? filename.substring(dotIndex + 1) : "";
    }

    private boolean startsWith(byte[] source, byte[] prefix) {
        if (source.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (source[i] != prefix[i]) return false;
        }
        return true;
    }

    private boolean containsAt(byte[] source, byte[] target, int offset) {
        if (source.length < offset + target.length) return false;
        for (int i = 0; i < target.length; i++) {
            if (source[offset + i] != target[i]) return false;
        }
        return true;
    }
}
