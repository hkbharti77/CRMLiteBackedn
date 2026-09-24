package com.chatcrmlite.backend.dtos.sms;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SmsSendRequest {
    private String businessId;
    private UUID contactId;
    private UUID campaignId;
    private UUID campaignRecipientId;
    private String phoneNumber; // E.164 formatted number (+919876543210 / +12025550143)
    private String messageContent;
    private String senderId;
    
    // DLT & Compliance parameters (India)
    private String dltEntityId;
    private String dltTemplateId;
    private String dltHeaderId;
    
    private Map<String, String> templateVariables;
}
