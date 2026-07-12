package com.chatcrmlite.backend.services.lead;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.LeadRepository;
import com.chatcrmlite.backend.repositories.TenantRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.ContactResolutionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
public class ConcurrencyLeadTest {

    @Autowired
    private ContactResolutionService contactResolutionService;

    @Autowired
    private ContactRepository contactRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TenantRepository tenantRepository;

    private User testUser;
    private Tenant testTenant;

    @BeforeEach
    public void setup() {
        testTenant = new Tenant();
        testTenant.setBusinessName("Concurrency Business");

        testUser = new User();
        testUser.setEmail("concurrency@example.com");
        testUser.setDisplayName("Concurrency User");
        testUser.setTenant(testTenant);
        testUser = userRepository.save(testUser);
    }

    @AfterEach
    public void cleanup() {
        contactRepository.deleteAll();
        userRepository.deleteAll();
        tenantRepository.deleteAll();
    }

    @Test
    public void testConcurrentContactCreationAvoidsDuplicates() throws InterruptedException {
        int threads = 10;
        ExecutorService executorService = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        
        String waId = "919999999999";
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            executorService.submit(() -> {
                try {
                    latch.await();
                    contactResolutionService.resolveContact(waId, "Concurrent User", testUser);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        // Start all threads simultaneously
        latch.countDown();
        done.await();

        long count = contactRepository.count();
        assertThat(count).isEqualTo(1);
        // Expecting one thread to create the contact and the rest to fetch it successfully due to our fix
        assertThat(successCount.get()).isEqualTo(threads);
        assertThat(failureCount.get()).isEqualTo(0);
    }
}
