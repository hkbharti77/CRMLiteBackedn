package com.chatcrmlite.backend.integration;

import com.chatcrmlite.backend.services.WebhookIngressService;
import com.chatcrmlite.backend.services.WebhookSignatureService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "whatsapp.webhook.skip-signature-verification=true")
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class WhatsAppWebhookSkipSignatureIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WebhookSignatureService signatureService;

    @MockBean
    private WebhookIngressService webhookIngressService;

    @Test
    void testWebhookPost_BypassVerification() throws Exception {
        String payload = "{}";
        String signature = "sha256=invalid";

        mockMvc.perform(post("/api/v1/webhook/whatsapp")
                        .header("X-Hub-Signature-256", signature)
                        .content(payload)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        verify(webhookIngressService, times(1)).ingress(payload);
        verify(signatureService, never()).verifySignature(anyString(), anyString());
    }
}
