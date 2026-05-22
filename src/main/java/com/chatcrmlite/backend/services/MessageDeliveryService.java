package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.dto.MenuDto;

public interface MessageDeliveryService {
    void sendMessage(String to, String text, String token, String phoneNumberId);
    void sendInteractiveMenu(String to, MenuDto menu, String token, String phoneNumberId);
    void markAsRead(String messageId, String token, String phoneNumberId);
}
