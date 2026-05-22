package com.chatcrmlite.backend.integration;

import com.chatcrmlite.backend.services.WebhookSignatureService;
import com.chatcrmlite.backend.services.WebhookIngressService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class WhatsAppWebhookSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WebhookSignatureService signatureService;

    @MockBean
    private WebhookIngressService webhookIngressService;

    @Test
    void testWebhookPost_ValidSignature() throws Exception {
        String payload = "{}";
        String signature = "sha256=valid";

        when(signatureService.verifySignature(anyString(), anyString())).thenReturn(true);
        when(signatureService.isTimestampValid(anyString())).thenReturn(true);

        mockMvc.perform(post("/api/v1/webhook/whatsapp")
                        .header("X-Hub-Signature-256", signature)
                        .content(payload)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        verify(webhookIngressService, times(1)).ingress(payload);
    }

    @Test
    void testWebhookPost_WithSubscribeParamsAndPayload_ProcessesPayload() throws Exception {
        String payload = "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"changes\":[{\"field\":\"messages\"}]}]}";
        String signature = "sha256=valid";

        when(signatureService.verifySignature(anyString(), anyString())).thenReturn(true);
        when(signatureService.isTimestampValid(anyString())).thenReturn(true);

        mockMvc.perform(post("/api/v1/webhook/whatsapp")
                        .queryParam("hub.mode", "subscribe")
                        .queryParam("hub.verify_token", "token-from-meta")
                        .queryParam("hub.challenge", "test1234")
                        .header("X-Hub-Signature-256", signature)
                        .content(payload)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        verify(webhookIngressService, times(1)).ingress(payload);
    }

    @Test
    void testWebhookPost_InvalidSignature() throws Exception {
        String payload = "{}";
        String signature = "sha256=invalid";

        when(signatureService.verifySignature(anyString(), anyString())).thenReturn(false);

        mockMvc.perform(post("/api/v1/webhook/whatsapp")
                        .header("X-Hub-Signature-256", signature)
                        .content(payload)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());

        verify(webhookIngressService, never()).ingress(anyString());
    }

    @Test
    void testWebhookPost_InvalidTimestamp() throws Exception {
        String payload = "{}";
        String signature = "sha256=valid";

        when(signatureService.verifySignature(anyString(), anyString())).thenReturn(true);
        when(signatureService.isTimestampValid(anyString())).thenReturn(false);

        mockMvc.perform(post("/api/v1/webhook/whatsapp")
                        .header("X-Hub-Signature-256", signature)
                        .content(payload)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());

        verify(webhookIngressService, never()).ingress(anyString());
    }

}
