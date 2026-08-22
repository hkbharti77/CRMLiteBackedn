package com.chatcrmlite.backend.clients;

import com.chatcrmlite.backend.dto.MenuDto;

public interface WhatsAppClient {
    /**
     * Sends a message through the WhatsApp Business API.
     * @param to The recipient's WhatsApp ID (phone number).
     * @param text The text body of the message.
     * @param accessToken The tenant's WhatsApp Access Token.
     * @param phoneNumberId The tenant's Phone Number ID.
     * @return The message ID from Meta's API.
     */
    String sendMessage(String to, String text, String accessToken, String phoneNumberId);

    /**
     * Sends an image message through the WhatsApp API.
     * @param to The recipient
     * @param imageUrl The URL of the image
     * @param caption Optional caption for the image
     */
    String sendImage(String to, String imageUrl, String caption, String accessToken, String phoneNumberId);

    /**
     * Sends an interactive list or button menu message through the WhatsApp Business API.
     */
    String sendInteractiveMenu(String to, MenuDto menu, String accessToken, String phoneNumberId);

    /**
     * Marks an incoming WhatsApp message as read (shows blue double-tick ✓✓ to the sender).
     * Should be called immediately after a customer message is processed.
     *
     * @param waMessageId  The wamid of the incoming message (from webhook payload).
     * @param accessToken  The tenant's access token.
     * @param phoneNumberId The tenant's phone number ID.
     */
    void markAsRead(String waMessageId, String accessToken, String phoneNumberId);

    /**
     * Sends a location message through the WhatsApp Business API.
     */
    String sendLocation(String to, double latitude, double longitude, String name, String address, String accessToken, String phoneNumberId);

    /**
     * Sends a catalog message through the WhatsApp Business API.
     */
    String sendCatalogMessage(String to, String text, String accessToken, String phoneNumberId);

    /**
     * Sends an interactive WhatsApp Flow message to a recipient.
     */
    String sendFlowMessage(String to, String headerText, String bodyText, String footerText,
                           String metaFlowId, String ctaText, String flowToken, String screen,
                           String accessToken, String phoneNumberId);

    /**
     * Fetches metadata for a given Meta media_id from the Meta Graph API.
     * @param mediaId The Meta media ID
     * @param accessToken The tenant WhatsApp Access Token
     * @return MetaMediaDto containing download url, mime_type, file_size, sha256
     */
    com.chatcrmlite.backend.dto.MetaMediaDto fetchMediaMetadata(String mediaId, String accessToken);

    /**
     * Downloads binary media content from the given Meta media URL.
     * @param mediaUrl The temporary download URL returned from Meta Graph API
     * @param accessToken The tenant WhatsApp Access Token
     * @return Raw byte array of the downloaded media
     */
    byte[] downloadMedia(String mediaUrl, String accessToken);

    /**
     * Functional interface for processing an InputStream directly during streaming.
     */
    @FunctionalInterface
    interface MediaStreamConsumer<T> {
        T consume(java.io.InputStream stream) throws Exception;
    }

    /**
     * Streams media from Meta URL directly to a consumer with bounded size protection.
     * @param mediaUrl The temporary download URL returned from Meta Graph API
     * @param accessToken The tenant WhatsApp Access Token
     * @param maxSizeBytes The maximum allowed byte limit to enforce during streaming
     * @param consumer The consumer callback to process the stream
     * @return The result produced by the consumer
     */
    <T> T streamMedia(String mediaUrl, String accessToken, long maxSizeBytes, MediaStreamConsumer<T> consumer);
}

