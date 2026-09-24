package com.chatcrmlite.backend.models.sms;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SmsProviderCredentials {
    private String accountSid;   // Twilio Account SID / Account ID
    private String authToken;    // Twilio Auth Token
    private String apiKey;       // MSG91 / Fast2SMS API Key
    private String apiSecret;    // AWS Secret Key / Provider Secret
    private String region;       // AWS Region / Provider Region
    private String senderId;     // Header / Sender Phone Number
    private Map<String, String> customHeaders;
}
