package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.clients.WhatsAppClient;
import com.chatcrmlite.backend.dto.MenuDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class MessageDeliveryServiceImpl implements MessageDeliveryService {

    @Autowired
    private WhatsAppClient whatsappClient;

    @Override
    public void sendMessage(String to, String text, String token, String phoneNumberId) {
        try {
            whatsappClient.sendMessage(to, text, token, phoneNumberId);
        } catch (Exception e) {
            log.error("[Delivery] Failed to send message to {}: {}", to, e.getMessage());
        }
    }

    @Override
    public void sendInteractiveMenu(String to, MenuDto menu, String token, String phoneNumberId) {
        try {
            whatsappClient.sendInteractiveMenu(to, menu, token, phoneNumberId);
        } catch (Exception e) {
            log.warn("[Delivery] Failed to send interactive menu to {}: {}", to, e.getMessage());
            // Fallback to text if menu fails
            if (menu.getBodyText() != null) {
                sendMessage(to, menu.getBodyText(), token, phoneNumberId);
            }
        }
    }

    @Override
    public void markAsRead(String messageId, String token, String phoneNumberId) {
        try {
            whatsappClient.markAsRead(messageId, token, phoneNumberId);
        } catch (Exception e) {
            log.warn("[Delivery] Could not mark message as read: {}", e.getMessage());
        }
    }
}
