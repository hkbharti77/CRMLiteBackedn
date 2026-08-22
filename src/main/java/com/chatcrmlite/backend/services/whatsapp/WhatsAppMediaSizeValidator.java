package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.config.WhatsAppMediaProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class WhatsAppMediaSizeValidator {

    private final WhatsAppMediaProperties properties;

    private static final Set<String> SUPPORTED_MEDIA_TYPES = Set.of(
            "IMAGE", "VIDEO", "AUDIO", "VOICE", "DOCUMENT", "STICKER", "RAW"
    );

    /**
     * Resolves the maximum allowed size limit in bytes for the specified media type.
     */
    public long getMaxLimitBytes(String mediaType) {
        return properties.getMaxLimitBytes(mediaType);
    }

    /**
     * Centralized validation of reported media size from Meta metadata.
     * Returns true if valid/within limits, or false if oversized or invalid.
     */
    public boolean validateReportedSize(String mediaType, Long fileSize) {
        if (fileSize == null) {
            // Unknown file size: allowed to proceed; bounded streaming will guard heap consumption.
            return true;
        }

        if (fileSize < 0) {
            log.warn("⚠️ [Media-Validation] Invalid negative file size reported: {} bytes for mediaType={}", fileSize, mediaType);
            return false;
        }

        long maxLimit = getMaxLimitBytes(mediaType);
        if (fileSize > maxLimit) {
            log.warn("🚨 [Media-Validation] Media size {} bytes exceeds configured limit of {} bytes for mediaType={}",
                    fileSize, maxLimit, mediaType);
            return false;
        }

        return true;
    }

    /**
     * Validates whether the media type is supported.
     */
    public boolean isSupportedMediaType(String mediaType) {
        if (mediaType == null || mediaType.isBlank()) {
            return false;
        }
        return SUPPORTED_MEDIA_TYPES.contains(mediaType.toUpperCase().trim());
    }
}
