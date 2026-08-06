package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.dto.CustomEmailDTO;
import com.chatcrmlite.backend.models.CustomEmail;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.email.EmailCampaignRecipient;
import com.chatcrmlite.backend.repositories.CustomEmailRepository;
import com.chatcrmlite.backend.repositories.email.EmailCampaignRecipientRepository;
import com.chatcrmlite.backend.services.email.EmailCampaignStateService;
import com.chatcrmlite.backend.services.email.EmailTrackingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.mail.javamail.JavaMailSender;

import jakarta.mail.internet.MimeMessage;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.chatcrmlite.backend.dto.AiContentResponse;
import com.chatcrmlite.backend.dto.AiTemplateResponse;
import com.chatcrmlite.backend.services.AIQuotaService;
import com.chatcrmlite.backend.services.TokenBudgetService;
import com.chatcrmlite.backend.services.CostTracker;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.data.message.AiMessage;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CustomEmailServiceTest {

    @Mock
    private CustomEmailRepository customEmailRepository;
    @Mock
    private EmailCampaignRecipientRepository recipientRepository;
    @Mock
    private EmailCampaignStateService stateService;
    @Mock
    private JavaMailSender mailSender;
    @Mock
    private org.thymeleaf.TemplateEngine templateEngine;
    @Mock
    private EmailTrackingService trackingService;
    
    @Mock
    private AIQuotaService aiQuotaService;
    @Mock
    private ChatLanguageModel chatLanguageModel;
    @Mock
    private TokenBudgetService tokenBudgetService;
    @Mock
    private CostTracker costTracker;
    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private CustomEmailService customEmailService;

    private CustomEmail campaign;
    private User owner;
    private Tenant tenant;
    private UUID campaignId;

    @BeforeEach
    void setUp() {
        tenant = new Tenant();
        tenant.setId(UUID.randomUUID());

        owner = new User();
        owner.setId(UUID.randomUUID());
        owner.setTenant(tenant);
        owner.setBusinessName("Test Business");

        campaignId = UUID.randomUUID();
        campaign = new CustomEmail();
        campaign.setId(campaignId);
        campaign.setOwner(owner);
        campaign.setStatus(CustomEmail.EmailStatus.SENDING);
        campaign.setSubject("Test Subject");
        campaign.setBody("Test Body");
    }

    @Test
    void testStartCampaignExecution_PausesWhenStatusNotSending() {
        campaign.setStatus(CustomEmail.EmailStatus.PAUSED);
        when(customEmailRepository.findById(campaignId)).thenReturn(Optional.of(campaign));

        customEmailService.startCampaignExecution(campaignId);

        verify(recipientRepository, never()).findByCampaignIdAndDeliveryStatus(any(), any(), any());
    }

    @Test
    void testStartCampaignExecution_CompletesWhenNoPendingRecipients() {
        when(customEmailRepository.findById(campaignId)).thenReturn(Optional.of(campaign));
        when(recipientRepository.findByCampaignIdAndDeliveryStatus(eq(campaignId), eq(EmailCampaignRecipient.DeliveryStatus.PENDING), any(Pageable.class)))
                .thenReturn(new PageImpl<>(Collections.emptyList()));

        customEmailService.startCampaignExecution(campaignId);

        verify(stateService).transitionState(campaign, CustomEmail.EmailStatus.COMPLETED, null);
        verify(mailSender, never()).send(any(MimeMessage.class));
    }
    
    @Test
    void testCancelCampaign() {
        when(customEmailRepository.findById(campaignId)).thenReturn(Optional.of(campaign));
        
        EmailCampaignRecipient pendingRecipient = new EmailCampaignRecipient();
        pendingRecipient.setId(UUID.randomUUID());
        pendingRecipient.setDeliveryStatus(EmailCampaignRecipient.DeliveryStatus.PENDING);
        
        when(recipientRepository.findByCampaignIdAndDeliveryStatus(eq(campaignId), eq(EmailCampaignRecipient.DeliveryStatus.PENDING), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(pendingRecipient)));

        CustomEmailDTO result = customEmailService.cancelCampaign(campaignId, owner);

        verify(stateService).transitionState(campaign, CustomEmail.EmailStatus.CANCELLED, owner);
        assertEquals(EmailCampaignRecipient.DeliveryStatus.FAILED, pendingRecipient.getDeliveryStatus());
        assertEquals("Campaign Cancelled", pendingRecipient.getFailureMessage());
        verify(recipientRepository).saveAll(anyList());
    }

    @Test
    void testGenerateAiContent_SanitizesHtml() throws Exception {
        String unsafeJson = "{\"subject\":\"Test\",\"body\":\"<p>Hello</p><script>alert(1)</script>\",\"ctaLabel\":\"\",\"ctaUrl\":\"\"}";
        AiMessage message = new AiMessage(unsafeJson);
        Response<AiMessage> mockResponse = new Response<>(message, new TokenUsage(10, 10), null);
        
        when(chatLanguageModel.generate(anyList())).thenReturn(mockResponse);
        when(objectMapper.copy()).thenReturn(new ObjectMapper());

        AiContentResponse result = customEmailService.generateAiContent(owner, "Write an email");

        assertEquals("Test", result.getSubject());
        assertFalse(result.getHtmlContent().contains("<script>"));
        assertTrue(result.getHtmlContent().contains("<p>Hello</p>"));
    }

    @Test
    void testGenerateAiTemplate_AppendsUnsubscribeAndSanitizes() throws Exception {
        String templateJson = "{\"subject\":\"Promo\",\"body\":\"<!DOCTYPE html><html><body><h1>Promo!</h1><iframe src='bad'></iframe></body></html>\"}";
        AiMessage message = new AiMessage(templateJson);
        Response<AiMessage> mockResponse = new Response<>(message, new TokenUsage(10, 10), null);
        
        when(chatLanguageModel.generate(anyList())).thenReturn(mockResponse);
        when(objectMapper.copy()).thenReturn(new ObjectMapper());

        AiTemplateResponse result = customEmailService.generateAiTemplate(owner, "Make a template");

        assertEquals("Promo", result.getSubject());
        assertTrue(result.getHtml().contains("{{unsubscribe_link}}"));
        assertFalse(result.getHtml().contains("<iframe"));
    }
}
