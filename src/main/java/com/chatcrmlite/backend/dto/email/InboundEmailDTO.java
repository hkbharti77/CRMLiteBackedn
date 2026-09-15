package com.chatcrmlite.backend.dto.email;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InboundEmailDTO {
    private String provider;
    private String providerMessageId;
    private String fromEmail;
    private String toEmail;
    private String recipientReplyToken;
    private String inReplyTo;
    private String references;
    private String subject;
    private String textBody;
    private String htmlBody;
    private Map<String, String> headers;
    private Instant receivedAt;
    private String rawPayload;
}
