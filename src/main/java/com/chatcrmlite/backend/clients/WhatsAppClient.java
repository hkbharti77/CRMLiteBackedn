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
}

