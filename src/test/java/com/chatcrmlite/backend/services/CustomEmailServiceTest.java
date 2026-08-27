package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.dto.CustomEmailDTO;
import com.chatcrmlite.backend.models.CustomEmail;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.email.EmailCampaignRecipient;
import com.chatcrmlite.backend.repositories.CustomEmailRepository;
import com.chatcrmlite.backend.repositories.TenantRepository;
import com.chatcrmlite.backend.repositories.email.EmailCampaignRecipientRepository;
import com.chatcrmlite.backend.services.email.EmailCampaignAuditService;
import com.chatcrmlite.backend.services.email.EmailCampaignStateService;
import com.chatcrmlite.backend.services.email.EmailProviderService;
import com.chatcrmlite.backend.services.email.EmailTrackingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import java.util.Optional;
import java.util.UUID;

import com.chatcrmlite.backend.dto.AiContentResponse;
import com.chatcrmlite.backend.dto.AiTemplateResponse;
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
    private TenantRepository tenantRepository;
    @Mock
    private EmailCampaignRecipientRepository recipientRepository;
    @Mock
    private EmailCampaignStateService stateService;
    @Mock
    private EmailCampaignAuditService auditService;
    @Mock
    private EmailProviderService emailProviderService;
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

    private CustomEmail campaignA;
    private CustomEmail campaignB;
    private User tenantAUser;
    private User tenantBUser;
    private Tenant tenantA;
    private Tenant tenantB;
    private UUID campaignAId;
    private UUID campaignBId;

    @BeforeEach
    void setUp() {
        tenantA = new Tenant();
        tenantA.setId(UUID.randomUUID());
        tenantA.setBusinessName("Tenant A Corp");

        tenantB = new Tenant();
        tenantB.setId(UUID.randomUUID());
        tenantB.setBusinessName("Tenant B Corp");

        tenantAUser = new User();
        tenantAUser.setId(UUID.randomUUID());
        tenantAUser.setTenant(tenantA);
        tenantAUser.setBusinessName("Tenant A Corp");
        tenantAUser.setEmail("owner@tenanta.com");

        tenantBUser = new User();
        tenantBUser.setId(UUID.randomUUID());
        tenantBUser.setTenant(tenantB);
        tenantBUser.setBusinessName("Tenant B Corp");
        tenantBUser.setEmail("owner@tenantb.com");

        campaignAId = UUID.randomUUID();
        campaignA = new CustomEmail();
        campaignA.setId(campaignAId);
        campaignA.setOwner(tenantAUser);
        campaignA.setStatus(CustomEmail.EmailStatus.SENDING);
        campaignA.setSubject("Campaign A Subject");
        campaignA.setBody("<p>Campaign A Body</p>");

        campaignBId = UUID.randomUUID();
        campaignB = new CustomEmail();
        campaignB.setId(campaignBId);
        campaignB.setOwner(tenantBUser);
        campaignB.setStatus(CustomEmail.EmailStatus.DRAFT);
        campaignB.setSubject("Campaign B Subject");
        campaignB.setBody("<p>Campaign B Body</p>");

        org.springframework.test.util.ReflectionTestUtils.setField(customEmailService, "aiQuotaService", aiQuotaService);
        org.springframework.test.util.ReflectionTestUtils.setField(customEmailService, "chatLanguageModel", chatLanguageModel);
        org.springframework.test.util.ReflectionTestUtils.setField(customEmailService, "tokenBudgetService", tokenBudgetService);
        org.springframework.test.util.ReflectionTestUtils.setField(customEmailService, "costTracker", costTracker);
        org.springframework.test.util.ReflectionTestUtils.setField(customEmailService, "objectMapper", objectMapper);
        org.springframework.test.util.ReflectionTestUtils.setField(customEmailService, "tenantRepository", tenantRepository);
        org.springframework.test.util.ReflectionTestUtils.setField(customEmailService, "auditService", auditService);
        org.springframework.test.util.ReflectionTestUtils.setField(customEmailService, "emailProviderService", emailProviderService);
    }

    // ── SECURITY & TENANT ISOLATION TESTS ───────────────────────────────────

    @Test
    @DisplayName("TEST 1: Tenant A owns campaign A → sendTestEmail succeeds")
    void testSendTestEmail_TenantAOwnsCampaignA_Succeeds() {
        when(customEmailRepository.findById(campaignAId)).thenReturn(Optional.of(campaignA));
        when(trackingService.generateTrackingToken()).thenReturn("token-123");
        when(trackingService.getUnsubscribeUrl("token-123")).thenReturn("https://unsub.com");
        when(tenantRepository.findById(tenantA.getId())).thenReturn(Optional.of(tenantA));
        when(trackingService.rewriteLinks(any(), any(), any(), any())).thenReturn("<html>rewritten</html>");
        when(trackingService.injectTrackingPixel(any(), any())).thenReturn("<html>pixel</html>");
        when(trackingService.appendUnsubscribeFooter(any(), any())).thenReturn("<html>footer</html>");
        when(emailProviderService.getDefaultProvider(any())).thenReturn(Optional.empty());
        when(mailSender.createMimeMessage()).thenReturn(mock(MimeMessage.class));

        String result = customEmailService.sendTestEmail(campaignAId, "test@tenanta.com", tenantAUser);

        assertEquals("Sent successfully", result);
        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("TEST 2: Tenant A attempts sendTestEmail for Tenant B campaign → ACCESS DENIED & no email sent")
    void testSendTestEmail_CrossTenant_AccessDenied() {
        when(customEmailRepository.findById(campaignBId)).thenReturn(Optional.of(campaignB));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                customEmailService.sendTestEmail(campaignBId, "attacker@tenanta.com", tenantAUser)
        );
        assertEquals("Campaign not found or access denied", ex.getMessage());
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("TEST 3: Tenant A attempts pauseCampaign on Tenant B campaign → DENIED & no state change")
    void testPauseCampaign_CrossTenant_Denied() {
        when(customEmailRepository.findById(campaignBId)).thenReturn(Optional.of(campaignB));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                customEmailService.pauseCampaign(campaignBId, tenantAUser)
        );
        assertEquals("Campaign not found or access denied", ex.getMessage());
        verify(stateService, never()).transitionState(any(), any(), any());
        assertEquals(CustomEmail.EmailStatus.DRAFT, campaignB.getStatus());
    }

    @Test
    @DisplayName("TEST 4: Tenant A attempts resumeCampaign on Tenant B campaign → DENIED & no state change")
    void testResumeCampaign_CrossTenant_Denied() {
        when(customEmailRepository.findById(campaignBId)).thenReturn(Optional.of(campaignB));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                customEmailService.resumeCampaign(campaignBId, tenantAUser)
        );
        assertEquals("Campaign not found or access denied", ex.getMessage());
        verify(stateService, never()).transitionState(any(), any(), any());
        assertEquals(CustomEmail.EmailStatus.DRAFT, campaignB.getStatus());
    }

    @Test
    @DisplayName("TEST 5: Tenant A attempts cancelCampaign on Tenant B campaign → DENIED & no state change")
    void testCancelCampaign_CrossTenant_Denied() {
        when(customEmailRepository.findById(campaignBId)).thenReturn(Optional.of(campaignB));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                customEmailService.cancelCampaign(campaignBId, tenantAUser)
        );
        assertEquals("Campaign not found or access denied", ex.getMessage());
        verify(stateService, never()).transitionState(any(), any(), any());
        assertEquals(CustomEmail.EmailStatus.DRAFT, campaignB.getStatus());
    }

    @Test
    @DisplayName("TEST 6: Campaign does not exist → safe not found behavior")
    void testCampaignNotFound_ThrowsSafeException() {
        UUID randomId = UUID.randomUUID();
        when(customEmailRepository.findById(randomId)).thenReturn(Optional.empty());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                customEmailService.pauseCampaign(randomId, tenantAUser)
        );
        assertEquals("Campaign not found or access denied", ex.getMessage());
    }

    @Test
    @DisplayName("TEST 7: Actor has no tenant → DENIED")
    void testActorWithoutTenant_Denied() {
        User userWithoutTenant = new User();
        userWithoutTenant.setId(UUID.randomUUID());
        userWithoutTenant.setTenant(null);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                customEmailService.pauseCampaign(campaignAId, userWithoutTenant)
        );
        assertEquals("Campaign not found or access denied", ex.getMessage());
    }

    @Test
    @DisplayName("TEST 8: Campaign owner is null → DENIED safely")
    void testCampaignOwnerNull_Denied() {
        CustomEmail orphanCampaign = new CustomEmail();
        orphanCampaign.setId(UUID.randomUUID());
        orphanCampaign.setOwner(null);

        when(customEmailRepository.findById(orphanCampaign.getId())).thenReturn(Optional.of(orphanCampaign));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                customEmailService.pauseCampaign(orphanCampaign.getId(), tenantAUser)
        );
        assertEquals("Campaign not found or access denied", ex.getMessage());
    }

    @Test
    @DisplayName("TEST 9: Campaign owner has no tenant → DENIED safely")
    void testCampaignOwnerWithoutTenant_Denied() {
        User ownerNoTenant = new User();
        ownerNoTenant.setId(UUID.randomUUID());
        ownerNoTenant.setTenant(null);

        CustomEmail brokenCampaign = new CustomEmail();
        brokenCampaign.setId(UUID.randomUUID());
        brokenCampaign.setOwner(ownerNoTenant);

        when(customEmailRepository.findById(brokenCampaign.getId())).thenReturn(Optional.of(brokenCampaign));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                customEmailService.pauseCampaign(brokenCampaign.getId(), tenantAUser)
        );
        assertEquals("Campaign not found or access denied", ex.getMessage());
    }

    // ── FUNCTIONAL REGRESSION TESTS ─────────────────────────────────────────

    @Test
    void testStartCampaignExecution_PausesWhenStatusNotSending() {
        campaignA.setStatus(CustomEmail.EmailStatus.PAUSED);
        when(customEmailRepository.findById(campaignAId)).thenReturn(Optional.of(campaignA));

        customEmailService.startCampaignExecution(campaignAId);

        verify(recipientRepository, never()).findByCampaignIdAndDeliveryStatus(any(), any(), any());
    }

    @Test
    void testStartCampaignExecution_CompletesWhenNoPendingRecipients() {
        when(customEmailRepository.findById(campaignAId)).thenReturn(Optional.of(campaignA));
        when(recipientRepository.findByCampaignIdAndDeliveryStatus(eq(campaignAId), eq(EmailCampaignRecipient.DeliveryStatus.PENDING), any(Pageable.class)))
                .thenReturn(new PageImpl<>(Collections.emptyList()));

        customEmailService.startCampaignExecution(campaignAId);

        verify(stateService).transitionState(campaignA, CustomEmail.EmailStatus.COMPLETED, null);
        verify(mailSender, never()).send(any(MimeMessage.class));
    }
    
    @Test
    void testCancelCampaign_ValidOwner_Succeeds() {
        when(customEmailRepository.findById(campaignAId)).thenReturn(Optional.of(campaignA));
        
        EmailCampaignRecipient pendingRecipient = new EmailCampaignRecipient();
        pendingRecipient.setId(UUID.randomUUID());
        pendingRecipient.setDeliveryStatus(EmailCampaignRecipient.DeliveryStatus.PENDING);
        
        when(recipientRepository.findByCampaignIdAndDeliveryStatus(eq(campaignAId), eq(EmailCampaignRecipient.DeliveryStatus.PENDING), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(pendingRecipient)));

        CustomEmailDTO result = customEmailService.cancelCampaign(campaignAId, tenantAUser);

        verify(stateService).transitionState(campaignA, CustomEmail.EmailStatus.CANCELLED, tenantAUser);
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

        AiContentResponse result = customEmailService.generateAiContent(tenantAUser, "Write an email");

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

        AiTemplateResponse result = customEmailService.generateAiTemplate(tenantAUser, "Make a template");

        assertEquals("Promo", result.getSubject());
        assertTrue(result.getHtml().contains("{{unsubscribe_link}}"));
        assertFalse(result.getHtml().contains("<iframe"));
    }
}
