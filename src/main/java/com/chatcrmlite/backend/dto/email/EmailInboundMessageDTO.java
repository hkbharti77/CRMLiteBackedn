package com.chatcrmlite.backend.dto.email;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailInboundMessageDTO {
    private UUID id;
    private UUID campaignId;
    private UUID recipientId;
    private UUID campaignRecipientId;
    private String replyToken;
    private String provider;
    private String providerMessageId;
    private String messageId;
    private String inReplyTo;
    private String fromEmail;
    private String toEmail;
    private String subject;
    private String textBody;
    private String htmlBody;
    private String replySnippet;
    private String sentiment;
    private String attributionStatus;
    private Instant receivedAt;
    private Instant createdAt;
}
