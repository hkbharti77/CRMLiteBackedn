package com.chatcrmlite.backend.integration;

import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.IdempotencyService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@Slf4j
@SpringBootTest
@ActiveProfiles("test")
public class IdempotencyConcurrencyTest {

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private UserRepository userRepository;

    @MockBean
    private StringRedisTemplate redisTemplate;

    @MockBean
    private ValueOperations<String, String> valueOperations;

    private UUID testTenantId;

    @BeforeEach
    void setUp() {
        // Create a test user/tenant
        User user = User.builder()
                .email("test-tenant-" + UUID.randomUUID() + "@example.com")
                .password("password")
                .role(User.Role.OWNER)
                .build();
        user = userRepository.save(user);
        testTenantId = user.getId();

        // Mock Redis to always "allow" the first attempt (simulating a cache miss or first write)
        // We want to test the DATABASE UNIQUE constraint race condition specifically.
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
    }

    @Test
    void testConcurrentWebhookRetries_OnlyOneSucceeds() throws InterruptedException {
        int threadCount = 10;
        String waMessageId = "wamid." + UUID.randomUUID();
        
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    latch.await(); // Wait for all threads to be ready
                    try {
                        boolean result = idempotencyService.markAsProcessing(waMessageId, testTenantId);
                        if (result) {
                            successCount.incrementAndGet();
                        } else {
                            failureCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        log.error("[Test] Thread failed with exception: {}", e.getMessage(), e);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        latch.countDown(); // Release all threads at once
        executorService.shutdown();
        boolean finished = executorService.awaitTermination(20, TimeUnit.SECONDS);
        assertThat(finished).as("Executor should have finished").isTrue();

        // Assertions
        log.info("[Test] Final Success Count: {}, Failure Count: {}", successCount.get(), failureCount.get());
        assertThat(successCount.get()).as("One and only one thread should succeed").isEqualTo(1);
        assertThat(failureCount.get()).as("All other threads should report failure").isEqualTo(threadCount - 1);
    }
}
