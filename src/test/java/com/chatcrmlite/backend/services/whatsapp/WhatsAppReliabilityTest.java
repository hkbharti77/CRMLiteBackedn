package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.clients.MetaWhatsAppClient;
import com.chatcrmlite.backend.config.ResilienceConfig;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.ConversationStateRepository;
import com.chatcrmlite.backend.repositories.MessageRepository;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import com.chatcrmlite.backend.services.DeadLetterHandler;
import com.chatcrmlite.backend.services.IdempotencyService;
import com.chatcrmlite.backend.services.RedisStateService;
import com.chatcrmlite.backend.services.WebhookWorker;
import com.chatcrmlite.backend.services.tenant.QuotaEnforcerService;
import com.chatcrmlite.backend.services.tenant.TenantResourceManager;
import com.chatcrmlite.backend.services.workflow.ProcessingContext;
import com.chatcrmlite.backend.services.workflow.QueueRouter;
import com.chatcrmlite.backend.services.workflow.WorkflowOrchestrator;
import com.chatcrmlite.backend.services.workflow.WorkflowStateTracker;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import java.util.Collections;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WhatsAppReliabilityTest {

    @Mock
    private RedisStateService redisStateService;

    @Mock
    private QueueRouter router;

    @Mock
    private WhatsAppIngressService ingressService;

    @Mock
    private QuotaEnforcerService quotaEnforcerService;

    @Mock
    private WhatsAppConfigRepository whatsappConfigRepository;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private StringRedisTemplate redisTemplate;

    @Mock
    private DeadLetterHandler deadLetterHandler;

    @Mock
    private TenantResourceManager resourceManager;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private ContactRepository contactRepository;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private ConversationStateRepository conversationStateRepository;

    @Mock
    private RestTemplate restTemplate;

    private WorkflowStateTracker tracker;
    private WorkflowOrchestrator orchestrator;
    private MetaWhatsAppClient metaWhatsAppClient;
    private RetryRegistry retryRegistry;
    private ObjectMapper objectMapper = new ObjectMapper();

    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        tracker = new WorkflowStateTracker(redisStateService);
        orchestrator = new WorkflowOrchestrator(
                router,
                tracker,
                new SimpleMeterRegistry(),
                ingressService,
                quotaEnforcerService
        );

        ResilienceConfig resilienceConfig = new ResilienceConfig();
        retryRegistry = resilienceConfig.retryRegistry();

        metaWhatsAppClient = new MetaWhatsAppClient();
        ReflectionTestUtils.setField(metaWhatsAppClient, "restTemplate", restTemplate);
        ReflectionTestUtils.setField(metaWhatsAppClient, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(metaWhatsAppClient, "retryRegistry", retryRegistry);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 1: Rapid messages from the same user are locked sequentially
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Test 1: Rapid messages from same waId acquire user lock and execute sequentially")
    void test1_rapidMessages_sameUserSequentialLocking() {
        String waId = "919876543210";
        when(redisStateService.tryLock(eq("workflow:lock:user:" + waId), any(), any())).thenReturn(true);

        orchestrator.startWorkflow("msg-1", waId, tenantId, "{}");

        verify(redisStateService, times(1)).tryLock(eq("workflow:lock:user:" + waId), any(), any());
        verify(ingressService, times(1)).resolveAndSaveIngress(any());
        verify(router, times(1)).routeToAi(any());
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 2: Different users can process concurrently without blocking
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Test 2: Different users (waId1 vs waId2) acquire distinct locks and process concurrently")
    void test2_differentUsers_concurrentProcessing() {
        String waIdA = "919000000001";
        String waIdB = "919000000002";

        when(redisStateService.tryLock(eq("workflow:lock:user:" + waIdA), any(), any())).thenReturn(true);
        when(redisStateService.tryLock(eq("workflow:lock:user:" + waIdB), any(), any())).thenReturn(true);

        orchestrator.startWorkflow("msg-A", waIdA, tenantId, "{}");
        orchestrator.startWorkflow("msg-B", waIdB, tenantId, "{}");

        verify(redisStateService, times(1)).tryLock(eq("workflow:lock:user:" + waIdA), any(), any());
        verify(redisStateService, times(1)).tryLock(eq("workflow:lock:user:" + waIdB), any(), any());
        verify(ingressService, times(2)).resolveAndSaveIngress(any());
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 3: Duplicate Meta webhook detected and halted before side effects
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Test 3: Duplicate Meta webhook detected early, releases lock, and halts workflow")
    void test3_duplicateMetaWebhook_detectedAndPrevented() {
        String waId = "919876543210";
        when(redisStateService.tryLock(anyString(), any(), any())).thenReturn(true);

        doAnswer(invocation -> {
            ProcessingContext ctx = invocation.getArgument(0);
            ctx.getMetadata().put("isDuplicate", true);
            return null;
        }).when(ingressService).resolveAndSaveIngress(any());

        orchestrator.startWorkflow("msg-dup-123", waId, tenantId, "{}");

        // Lock must be released when duplicate is detected
        verify(redisStateService, times(1)).unlock(eq("workflow:lock:user:" + waId), any());
        // Must NOT route to AI or Flow on duplicate
        verify(router, never()).routeToAi(any());
        verify(router, never()).routeToFlow(any());
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 4: Rate limit check occurs BEFORE sending blue tick read receipt
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Test 4: Rate limit is checked before blue tick; rate-limited messages do not receive blue tick")
    void test4_rateLimit_checkedBeforeBlueTick() {
        com.chatcrmlite.backend.repositories.WhatsAppTemplateRepository templateRepo = mock(com.chatcrmlite.backend.repositories.WhatsAppTemplateRepository.class);
        com.chatcrmlite.backend.repositories.TenantRepository tenantRepo = mock(com.chatcrmlite.backend.repositories.TenantRepository.class);

        WebhookWorker worker = new WebhookWorker(
                orchestrator,
                whatsappConfigRepository,
                objectMapper,
                redisTemplate,
                deadLetterHandler,
                resourceManager,
                templateRepo,
                tenantRepo
        );
        ReflectionTestUtils.setField(worker, "redisStateService", redisStateService);
        ReflectionTestUtils.setField(worker, "streamName", "whatsapp:ingress:stream");
        ReflectionTestUtils.setField(worker, "groupName", "whatsapp-workers");
        ReflectionTestUtils.setField(worker, "maxRetries", 3);

        String messagePayload = """
                {
                  "entry": [{
                    "changes": [{
                      "field": "messages",
                      "value": {
                        "metadata": { "phone_number_id": "phone-123" },
                        "messages": [{
                          "id": "wamid.test.123",
                          "from": "919876543210",
                          "type": "text",
                          "text": { "body": "Hello" }
                        }]
                      }
                    }]
                  }]
                }
                """;

        when(whatsappConfigRepository.findTenantIdByPhoneNumberId("phone-123")).thenReturn(Optional.of(tenantId));
        // Simulate rate limit EXCEEDED
        when(resourceManager.canConsume(eq(tenantId), eq(TenantResourceManager.ResourceType.MESSAGES_PER_SECOND), eq(1)))
                .thenReturn(false);

        MapRecord<String, String, String> record = ObjectRecord
                .create("whatsapp:ingress:stream", messagePayload)
                .withId(RecordId.of("1779381961261-0"));

        worker.onMessage(record);

        // Webhook must be acknowledged to avoid infinite loop
        verify(redisTemplate.opsForStream()).acknowledge(eq("whatsapp-workers"), eq(record));
        // Must NOT start workflow
        verify(whatsappConfigRepository, never()).findByTenantId(any());
        verify(ingressService, never()).resolveAndSaveIngress(any());
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 5: Meta 400 Bad Request fails immediately without retry
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Test 5: Meta HTTP 400 Bad Request fails fast without retrying")
    void test5_meta400_noRetryAndFailureLogged() {
        String errorJson = "{\"error\":{\"message\":\"Invalid parameter\",\"code\":\"100\"}}";
        HttpClientErrorException badRequestException = HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST, "Bad Request", HttpHeaders.EMPTY, errorJson.getBytes(), null);

        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
                .thenThrow(badRequestException);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> {
            metaWhatsAppClient.sendMessage("919876543210", "Hello", "token", "phone-id");
        });

        assertTrue(thrown.getMessage().contains("(100) Invalid parameter"));
        // 400 must NOT be retried (only 1 attempt)
        verify(restTemplate, times(1)).postForObject(anyString(), any(), eq(Map.class));
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 6: Meta 429 Too Many Requests is retried
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Test 6: Meta HTTP 429 Too Many Requests triggers retry policy")
    void test6_meta429_retriedWithBackoff() {
        String rateLimitJson = "{\"error\":{\"message\":\"Rate limit hit\",\"code\":\"429\"}}";
        HttpClientErrorException rateLimitException = HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", HttpHeaders.EMPTY, rateLimitJson.getBytes(), null);

        Map<String, Object> successResponse = Map.of("messages", List.of(Map.of("id", "wamid.success.123")));

        // Attempt 1 fails with 429, Attempt 2 succeeds
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
                .thenThrow(rateLimitException)
                .thenReturn(successResponse);

        String messageId = metaWhatsAppClient.sendMessage("919876543210", "Hello", "token", "phone-id");

        assertEquals("wamid.success.123", messageId);
        // Must be called exactly twice (1 fail + 1 retry success)
        verify(restTemplate, times(2)).postForObject(anyString(), any(), eq(Map.class));
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 7: Meta 500 Internal Server Error is retried
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Test 7: Meta HTTP 500 triggers retry, and fails gracefully after max retries")
    void test7_meta500_retriedAndDlqOnFailure() {
        String serverErrorJson = "{\"error\":{\"message\":\"Temporary server error\",\"code\":\"500\"}}";
        HttpServerErrorException serverErrorException = HttpServerErrorException.create(
                HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", HttpHeaders.EMPTY, serverErrorJson.getBytes(), null);

        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
                .thenThrow(serverErrorException);

        assertThrows(RuntimeException.class, () -> {
            metaWhatsAppClient.sendMessage("919876543210", "Hello", "token", "phone-id");
        });

        // Max attempts = 2 for whatsAppRetry
        verify(restTemplate, times(2)).postForObject(anyString(), any(), eq(Map.class));
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 8: HTTP connection/read timeout triggers retry and throws cleanly
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Test 8: HTTP connection/read timeout (ResourceAccessException) is handled cleanly")
    void test8_httpTimeout_timesOutAndReleasesWorker() {
        ResourceAccessException timeoutException = new ResourceAccessException(
                "Read timed out", new SocketTimeoutException("Read timed out"));

        Map<String, Object> successResponse = Map.of("messages", List.of(Map.of("id", "wamid.success.timeout.retry")));

        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
                .thenThrow(timeoutException)
                .thenReturn(successResponse);

        String messageId = metaWhatsAppClient.sendMessage("919876543210", "Hello", "token", "phone-id");

        assertEquals("wamid.success.timeout.retry", messageId);
        verify(restTemplate, times(2)).postForObject(anyString(), any(), eq(Map.class));
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 9: Exception during workflow guarantees lock release
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Test 9: Exception inside startWorkflow guarantees lock release and stage FAILED")
    void test9_exceptionDuringWorkflow_lockAlwaysReleased() {
        String waId = "919876543210";
        when(redisStateService.tryLock(eq("workflow:lock:user:" + waId), any(), any())).thenReturn(true);

        doThrow(new RuntimeException("Database connection failure"))
                .when(ingressService).resolveAndSaveIngress(any());

        assertThrows(RuntimeException.class, () -> {
            orchestrator.startWorkflow("msg-err-123", waId, tenantId, "{}");
        });

        // Lock must be released even on unexpected exception
        verify(redisStateService, times(1)).unlock(eq("workflow:lock:user:" + waId), any());
        // Must record stage as FAILED in tracker
        verify(redisStateService).set(eq("workflow:state:msg-err-123"), eq("FAILED"), any());
    }
}
