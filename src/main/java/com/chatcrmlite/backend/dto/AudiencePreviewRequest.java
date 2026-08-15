package com.chatcrmlite.backend.dto;

import com.chatcrmlite.backend.models.CustomEmail;
import lombok.Data;

@Data
public class AudiencePreviewRequest {
    private CustomEmail.RecipientMode recipientMode;
    private Object tagsFilter; // Can be String or Map depending on how frontend sends it
    private String manualRecipients;
}
