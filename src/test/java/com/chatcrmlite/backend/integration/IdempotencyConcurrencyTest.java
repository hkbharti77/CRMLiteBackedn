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

    @Autowired
    private com.chatcrmlite.backend.repositories.ProcessedMessageRepository processedMessageRepository;

    @Autowired
    private com.chatcrmlite.backend.repositories.TenantRepository tenantRepository;

    @MockBean
    private StringRedisTemplate redisTemplate;

    @MockBean
    private ValueOperations<String, String> valueOperations;

    private UUID testTenantId;

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        processedMessageRepository.deleteAllInBatch();
        userRepository.deleteAll();
        tenantRepository.deleteAll();
    }

    @BeforeEach
    void setUp() {
        // Create and persist Tenant first to satisfy NOT NULL FK constraint
        com.chatcrmlite.backend.models.Tenant tenant = com.chatcrmlite.backend.models.Tenant.builder()
                .businessName("Idempotency Test Business")
                .businessType("GENERAL")
                .businessSubType("GENERAL")
                .build();
        tenant = tenantRepository.save(tenant);
        testTenantId = tenant.getId();

        // Create a test user attached to this tenant
        User user = User.builder()
                .email("test-tenant-" + UUID.randomUUID() + "@example.com")
                .password("password")
                .role(User.Role.OWNER)
                .tenant(tenant)
                .build();
        userRepository.save(user);

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
        java.util.List<Throwable> exceptions = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

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
                    } catch (org.springframework.dao.DataIntegrityViolationException e) {
                        failureCount.incrementAndGet(); // H2 constraint violation on commit
                    } catch (Exception e) {
                        exceptions.add(e);
                        System.out.println("[Test Thread] Exception: " + e.getMessage());
                        e.printStackTrace(System.out);
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
        System.out.println("[Test] Final Success Count: " + successCount.get() + ", Failure Count: " + failureCount.get());
        if (!exceptions.isEmpty()) {
            System.err.println("[Test] Got " + exceptions.size() + " unexpected exceptions:");
            exceptions.forEach(Throwable::printStackTrace);
        }
        
        assertThat(exceptions).as("There should be no unexpected exceptions").isEmpty();
        assertThat(successCount.get()).as("One and only one thread should succeed").isEqualTo(1);
        assertThat(failureCount.get()).as("All other threads should report failure").isEqualTo(threadCount - 1);
    }

    @Autowired
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    @Test
    void testNestedTransactionAvoidsUnexpectedRollbackException() throws InterruptedException {
        int threadCount = 2;
        String waMessageId = "wamid." + UUID.randomUUID();
        
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        AtomicInteger unexpectedRollbackCount = new AtomicInteger(0);
        java.util.List<Throwable> exceptions = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    latch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                
                try {
                    // Simulate WhatsAppIngressService outer transaction
                    transactionTemplate.execute(status -> {
                        boolean result = idempotencyService.markAsProcessing(waMessageId, testTenantId);
                        if (result) {
                            successCount.incrementAndGet();
                        } else {
                            failureCount.incrementAndGet();
                        }
                        return null;
                    });
                } catch (org.springframework.transaction.UnexpectedRollbackException e) {
                    unexpectedRollbackCount.incrementAndGet();
                } catch (org.springframework.dao.DataIntegrityViolationException e) {
                    failureCount.incrementAndGet();
                } catch (Exception e) {
                    exceptions.add(e);
                    System.out.println("[Test Thread] Exception: " + e.getMessage());
                    e.printStackTrace(System.out);
                }
            });
        }

        latch.countDown();
        executorService.shutdown();
        boolean finished = executorService.awaitTermination(20, TimeUnit.SECONDS);
        assertThat(finished).as("Executor should have finished").isTrue();

        System.out.println("[Test] Final Success Count: " + successCount.get() + ", Failure Count: " + failureCount.get() + ", Rollbacks: " + unexpectedRollbackCount.get());
        if (!exceptions.isEmpty()) {
            System.err.println("[Test] Got " + exceptions.size() + " unexpected exceptions:");
            exceptions.forEach(Throwable::printStackTrace);
        }

        assertThat(exceptions).as("There should be no unexpected exceptions").isEmpty();
        assertThat(successCount.get()).as("One thread succeeds").isEqualTo(1);
        assertThat(unexpectedRollbackCount.get() + failureCount.get()).as("One thread fails gracefully").isEqualTo(1);
    }
}
