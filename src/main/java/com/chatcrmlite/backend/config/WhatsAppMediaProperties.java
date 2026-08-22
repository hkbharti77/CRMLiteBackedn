package com.chatcrmlite.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "whatsapp.media")
public class WhatsAppMediaProperties {

    /**
     * Maximum allowed size for WhatsApp images in bytes (Default: 16 MB = 16,777,216).
     */
    private long maxImageSize = 16L * 1024 * 1024;

    /**
     * Maximum allowed size for WhatsApp videos in bytes (Default: 64 MB = 67,108,864).
     */
    private long maxVideoSize = 64L * 1024 * 1024;

    /**
     * Maximum allowed size for WhatsApp audio/voice in bytes (Default: 32 MB = 33,554,432).
     */
    private long maxAudioSize = 32L * 1024 * 1024;

    /**
     * Maximum allowed size for WhatsApp documents in bytes (Default: 100 MB = 104,857,600).
     */
    private long maxDocumentSize = 100L * 1024 * 1024;

    /**
     * Maximum allowed size for WhatsApp stickers in bytes (Default: 5 MB = 5,242,880).
     */
    private long maxStickerSize = 5L * 1024 * 1024;

    /**
     * Resolves the configured limit in bytes for a specified media type.
     */
    public long getMaxLimitBytes(String mediaType) {
        if (mediaType == null) {
            return maxDocumentSize;
        }
        return switch (mediaType.toUpperCase().trim()) {
            case "IMAGE" -> maxImageSize;
            case "VIDEO" -> maxVideoSize;
            case "AUDIO", "VOICE" -> maxAudioSize;
            case "STICKER" -> maxStickerSize;
            default -> maxDocumentSize;
        };
    }
}
