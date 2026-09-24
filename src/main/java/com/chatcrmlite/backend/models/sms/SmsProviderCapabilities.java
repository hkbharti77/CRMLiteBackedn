package com.chatcrmlite.backend.models.sms;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SmsProviderCapabilities {
    private boolean inboundSms;
    private boolean deliveryReports;
    private boolean unicode;
    private boolean scheduling;
    private boolean senderIdSupported;
    private boolean templateMessagingRequired;
    private boolean indiaDltRequired;
}
