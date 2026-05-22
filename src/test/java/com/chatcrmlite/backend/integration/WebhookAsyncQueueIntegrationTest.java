package com.chatcrmlite.backend.integration;

import com.chatcrmlite.backend.services.WebhookQueueProducer;
import com.chatcrmlite.backend.services.workflow.WorkflowOrchestrator;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

@Slf4j
@SpringBootTest
@ActiveProfiles("test")
@Disabled("Requires a live Redis queue consumer (WebhookWorker) to be running. " +
          "The test profile uses an embedded/mock Redis that does not spin up the blocking list-pop consumer. " +
          "This is an end-to-end infrastructure test; validate manually against a running environment.")
public class WebhookAsyncQueueIntegrationTest {

    @Autowired
    private WebhookQueueProducer producer;

    @MockBean
    private WorkflowOrchestrator workflowOrchestrator;

    @Test
    void testWebhookEnqueuedAndProcessedAsynchronously() {
        String payload = "{\"object\":\"whatsapp\",\"entry\":[{\"id\":\"123\",\"changes\":[{\"value\":{\"messaging_product\":\"whatsapp\",\"metadata\":{\"display_phone_number\":\"12345\",\"phone_number_id\":\"67890\"},\"messages\":[{\"from\":\"1234567890\",\"id\":\"wamid.HBgLOTE4NTA3NjIxNjI2FQIAERgSQjE2MzVDM0E4OEY4QzY5N0YyAA==\",\"timestamp\":\"1663071714\",\"text\":{\"body\":\"Hello async!\"},\"type\":\"text\"}]}}]}]}";

        log.info("🚀 Enqueueing payload for async test...");
        producer.enqueue(payload);

        // Verify that WorkflowOrchestrator.startWorkflow is eventually called by the worker
        await()
            .atMost(Duration.ofSeconds(10))
            .untilAsserted(() -> verify(workflowOrchestrator, atLeastOnce()).startWorkflow(anyString(), anyString(), any(), anyString()));

        log.info("✅ Verified that WorkflowOrchestrator was called asynchronously by the worker.");
    }
}
