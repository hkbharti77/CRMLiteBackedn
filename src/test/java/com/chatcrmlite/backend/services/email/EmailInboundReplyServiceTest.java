package com.chatcrmlite.backend.services.email;

import com.chatcrmlite.backend.dto.email.InboundEmailDTO;
import com.chatcrmlite.backend.models.email.EmailCampaignRecipient;
import com.chatcrmlite.backend.models.email.EmailInboundMessage;
import com.chatcrmlite.backend.models.email.EmailInboundMessage.AttributionStatus;
import com.chatcrmlite.backend.repositories.email.EmailCampaignRecipientRepository;
import com.chatcrmlite.backend.repositories.email.EmailInboundMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmailInboundReplyServiceTest {

    @Mock
    private EmailCampaignRecipientRepository recipientRepository;

    @Mock
    private EmailInboundMessageRepository inboundMessageRepository;

    private EmailInboundReplyService inboundReplyService;

    private UUID tenantId;
    private UUID campaignId;
    private EmailCampaignRecipient recipient;

    @BeforeEach
    void setUp() {
        inboundReplyService = new EmailInboundReplyService(recipientRepository, inboundMessageRepository);

        tenantId = UUID.randomUUID();
        campaignId = UUID.randomUUID();

        recipient = new EmailCampaignRecipient();
        recipient.setId(UUID.randomUUID());
        recipient.setTenantId(tenantId);
        recipient.setCampaignId(campaignId);
        recipient.setEmail("user@example.com");
        recipient.setReplyToken("test-reply-token-123");
        recipient.setLastMessageId("msg-uuid-123@gyanvaniai.online");
    }

    @Test
    @DisplayName("Process reply attributed via replyToken in DTO")
    void testAttributionViaReplyToken() {
        InboundEmailDTO dto = InboundEmailDTO.builder()
                .provider("sendgrid")
                .providerMessageId("sg-msg-1")
                .fromEmail("user@example.com")
                .toEmail("reply+test-reply-token-123@reply.gyanvaniai.online")
                .recipientReplyToken("test-reply-token-123")
                .subject("Re: Campaign")
                .textBody("I would like to know more!")
                .htmlBody("<p>I would like to know more!</p>")
                .receivedAt(Instant.now())
                .build();

        when(inboundMessageRepository.findByProviderAndProviderMessageId("sendgrid", "sg-msg-1"))
                .thenReturn(Optional.empty());
        when(recipientRepository.findByReplyToken("test-reply-token-123"))
                .thenReturn(Optional.of(recipient));
        when(inboundMessageRepository.saveAndFlush(any(EmailInboundMessage.class)))
                .thenAnswer(i -> {
                    EmailInboundMessage msg = i.getArgument(0);
                    msg.setId(UUID.randomUUID());
                    return msg;
                });

        EmailInboundMessage result = inboundReplyService.processInboundReply(dto);

        assertNotNull(result);
        assertEquals(AttributionStatus.ATTRIBUTED, result.getAttributionStatus());
        assertEquals(tenantId, result.getTenantId());
        assertEquals(campaignId, result.getCampaignId());
        assertEquals(recipient.getId(), result.getCampaignRecipientId());
        assertEquals("I would like to know more!", result.getReplySnippet());

        verify(recipientRepository).incrementReplyCountAtomic(eq(recipient.getId()), eq(tenantId), any());
    }

    @Test
    @DisplayName("Process reply attributed via In-Reply-To Message-ID fallback")
    void testAttributionViaInReplyToFallback() {
        InboundEmailDTO dto = InboundEmailDTO.builder()
                .provider("mailgun")
                .providerMessageId("mg-msg-2")
                .fromEmail("user@example.com")
                .toEmail("sales@company.com")
                .inReplyTo("<msg-uuid-123@gyanvaniai.online>")
                .subject("Re: Offer")
                .textBody("Yes I agree")
                .receivedAt(Instant.now())
                .build();

        when(inboundMessageRepository.findByProviderAndProviderMessageId("mailgun", "mg-msg-2"))
                .thenReturn(Optional.empty());
        when(recipientRepository.findByLastMessageId("msg-uuid-123@gyanvaniai.online"))
                .thenReturn(Optional.of(recipient));
        when(inboundMessageRepository.saveAndFlush(any(EmailInboundMessage.class)))
                .thenAnswer(i -> {
                    EmailInboundMessage msg = i.getArgument(0);
                    msg.setId(UUID.randomUUID());
                    return msg;
                });

        EmailInboundMessage result = inboundReplyService.processInboundReply(dto);

        assertEquals(AttributionStatus.ATTRIBUTED, result.getAttributionStatus());
        assertEquals(recipient.getId(), result.getCampaignRecipientId());
        verify(recipientRepository).incrementReplyCountAtomic(eq(recipient.getId()), eq(tenantId), any());
    }

    @Test
    @DisplayName("Process unattributed reply when no token or message-id matches")
    void testUnattributedReply() {
        InboundEmailDTO dto = InboundEmailDTO.builder()
                .provider("generic")
                .providerMessageId("gen-msg-3")
                .fromEmail("unknown@example.com")
                .toEmail("support@company.com")
                .subject("Hello")
                .textBody("Random question")
                .build();

        when(inboundMessageRepository.findByProviderAndProviderMessageId("generic", "gen-msg-3"))
                .thenReturn(Optional.empty());
        when(inboundMessageRepository.saveAndFlush(any(EmailInboundMessage.class)))
                .thenAnswer(i -> i.getArgument(0));

        EmailInboundMessage result = inboundReplyService.processInboundReply(dto);

        assertEquals(AttributionStatus.UNATTRIBUTED, result.getAttributionStatus());
        assertNull(result.getCampaignRecipientId());
        verify(recipientRepository, never()).incrementReplyCountAtomic(any(), any(), any());
    }

    @Test
    @DisplayName("Idempotency: Ignore duplicate providerMessageId without incrementing counter")
    void testDuplicateProviderMessageIdIgnored() {
        InboundEmailDTO dto = InboundEmailDTO.builder()
                .provider("sendgrid")
                .providerMessageId("dup-msg-id")
                .fromEmail("user@example.com")
                .build();

        EmailInboundMessage existingMsg = new EmailInboundMessage();
        existingMsg.setId(UUID.randomUUID());
        existingMsg.setProvider("sendgrid");
        existingMsg.setProviderMessageId("dup-msg-id");
        existingMsg.setAttributionStatus(AttributionStatus.ATTRIBUTED);

        when(inboundMessageRepository.findByProviderAndProviderMessageId("sendgrid", "dup-msg-id"))
                .thenReturn(Optional.of(existingMsg));

        EmailInboundMessage result = inboundReplyService.processInboundReply(dto);

        assertEquals(existingMsg.getId(), result.getId());
        verify(inboundMessageRepository, never()).saveAndFlush(any());
        verify(recipientRepository, never()).incrementReplyCountAtomic(any(), any(), any());
    }

    @Test
    @DisplayName("HTML Sanitization: Strip script and iframe tags")
    void testHtmlSanitization() {
        String unsafeHtml = "<div>Hello<script>alert('hack')</script><iframe src='evil.com'></iframe><b onclick='alert(1)'>Click</b></div>";

        String sanitized = inboundReplyService.sanitizeHtml(unsafeHtml);

        assertFalse(sanitized.contains("<script"));
        assertFalse(sanitized.contains("<iframe"));
        assertFalse(sanitized.contains("onclick="));
        assertTrue(sanitized.contains("<div>Hello"));
        assertTrue(sanitized.contains("Click</b>"));
    }
}
