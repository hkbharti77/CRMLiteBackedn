package com.chatcrmlite.backend.services.sms;

import com.chatcrmlite.backend.dtos.sms.SmsSendRequest;
import com.chatcrmlite.backend.dtos.sms.SmsSendResult;
import com.chatcrmlite.backend.models.sms.SmsProviderCapabilities;
import com.chatcrmlite.backend.models.sms.SmsProviderCredentials;

public interface SmsSenderProvider {
    /**
     * Unique Provider Key (e.g. TWILIO, MSG91, FAST2SMS, AWS_SNS)
     */
    String getProviderType();

    /**
     * Declare capabilities supported by this provider gateway
     */
    SmsProviderCapabilities getCapabilities();

    /**
     * Send outbound SMS using strongly-typed decrypted credentials
     */
    SmsSendResult sendSms(SmsSendRequest request, SmsProviderCredentials credentials);

    /**
     * Verify incoming webhook signature (HMAC-SHA256, Twilio X-Signature, etc.)
     */
    boolean verifyWebhookSignature(String payload, String signatureHeader, String providerSecret);
}
