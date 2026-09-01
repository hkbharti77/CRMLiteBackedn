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
import static org.mockito.ArgumentMatchers.eq;
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
        String payload = "{\"entry\":[{\"id\":\"123\"}]}";
        String signature = "sha256=validsignature";

        when(signatureService.verifySignature(eq(payload), eq(signature))).thenReturn(true);

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
        String signature = "sha256=validsignature";

        when(signatureService.verifySignature(eq(payload), eq(signature))).thenReturn(true);

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
    void testWebhookPost_MissingSignatureHeader_Rejected() throws Exception {
        String payload = "{\"entry\":[{\"id\":\"123\"}]}";

        when(signatureService.verifySignature(eq(payload), isNull())).thenReturn(false);

        mockMvc.perform(post("/api/v1/webhook/whatsapp")
                        .content(payload)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());

        verify(webhookIngressService, never()).ingress(anyString());
    }

    @Test
    void testWebhookPost_WithOldTimestamp_Accepted() throws Exception {
        // Simulates production payload containing an old message/retry timestamp (e.g. 84966 seconds old)
        long oldTimestamp = (System.currentTimeMillis() / 1000) - 84966;
        String payload = "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"changes\":[{\"value\":{\"messages\":[{\"timestamp\":\"" + oldTimestamp + "\"}]}}]}]}";
        String signature = "sha256=validsignature";

        when(signatureService.verifySignature(eq(payload), eq(signature))).thenReturn(true);

        mockMvc.perform(post("/api/v1/webhook/whatsapp")
                        .header("X-Hub-Signature-256", signature)
                        .content(payload)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        verify(webhookIngressService, times(1)).ingress(payload);
    }

    @Test
    void testWebhookPost_WithFutureTimestamp_Accepted() throws Exception {
        long futureTimestamp = (System.currentTimeMillis() / 1000) + 3600;
        String payload = "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"changes\":[{\"value\":{\"messages\":[{\"timestamp\":\"" + futureTimestamp + "\"}]}}]}]}";
        String signature = "sha256=validsignature";

        when(signatureService.verifySignature(eq(payload), eq(signature))).thenReturn(true);

        mockMvc.perform(post("/api/v1/webhook/whatsapp")
                        .header("X-Hub-Signature-256", signature)
                        .content(payload)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        verify(webhookIngressService, times(1)).ingress(payload);
    }
}
