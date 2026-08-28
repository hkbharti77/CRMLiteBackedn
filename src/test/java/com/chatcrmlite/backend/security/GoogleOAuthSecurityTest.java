package com.chatcrmlite.backend.security;

import com.chatcrmlite.backend.controllers.IntegrationController;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.GoogleCalendarService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
public class GoogleOAuthSecurityTest {

    @Autowired
    private IntegrationController integrationController;

    @MockBean
    private RedissonClient redissonClient;
    
    @MockBean
    private RBucket<UUID> rBucketMock;

    @MockBean
    private GoogleCalendarService googleCalendarService;

    @MockBean
    private UserRepository userRepository;

    private User userA;
    private User userB;

    @BeforeEach
    void setUp() throws Exception {
        userA = new User();
        userA.setId(UUID.randomUUID());
        userA.setEmail("usera@example.com");

        userB = new User();
        userB.setId(UUID.randomUUID());
        userB.setEmail("userb@example.com");

        when(userRepository.findByEmail("usera@example.com")).thenReturn(Optional.of(userA));
        when(userRepository.findByEmail("userb@example.com")).thenReturn(Optional.of(userB));
        
        doAnswer(inv -> "http://google.com/auth?state=" + inv.getArgument(0)).when(googleCalendarService).buildAuthorizationUrl(anyString());

        // Mock Redisson Bucket
        doReturn(rBucketMock).when(redissonClient).getBucket(anyString());
        // By default, the mock bucket will return userA's ID on getAndDelete (valid state)
        doReturn(userA.getId()).when(rBucketMock).getAndDelete();

        // Set authenticated user context
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("usera@example.com", null, null));
    }

    private String extractStateFromRedis() {
        // Redisson handles the real storage in the test environment using Redisson Spring Boot Starter (mocked or real testcontainers).
        // If RedissonClient is active, we can intercept the bucket creation or just run the actual authUrl to get the generated state.
        return null;
    }

    @Test
    void test1_ValidOAuthState() throws Exception {
        // Generate State
        ResponseEntity<Map<String, String>> response = integrationController.getAuthUrl();
        String url = response.getBody().get("url");
        String state = url.substring(url.indexOf("state=") + 6);

        // Callback with valid state
        ResponseEntity<Void> callbackResponse = integrationController.oauthCallback("VALID_CODE", state);
        
        // Assert successful redirect
        assertEquals(302, callbackResponse.getStatusCode().value());
        assertTrue(callbackResponse.getHeaders().getLocation().toString().contains("googleConnected=true"));

        // Verify token exchange called for User A
        verify(googleCalendarService).handleOAuthCallback("VALID_CODE", userA.getId());
    }

    @Test
    void test2_StateTampering() throws Exception {
        // Callback with invalid state -> mock bucket returns null
        doReturn(null).when(rBucketMock).getAndDelete();

        ResponseEntity<Void> callbackResponse = integrationController.oauthCallback("VALID_CODE", "tampered_state_value");
        
        assertEquals(302, callbackResponse.getStatusCode().value());
        assertTrue(callbackResponse.getHeaders().getLocation().toString().contains("googleError="));

        // Verify no token exchange
        verify(googleCalendarService, never()).handleOAuthCallback(anyString(), any(UUID.class));
    }

    @Test
    void test3_VictimUuidSubstitution() throws Exception {
        // Attempt to pass Victim B's UUID directly as state
        // In this case, the mock bucket returns null because UUID is not a valid recognized state key
        doReturn(null).when(rBucketMock).getAndDelete();
        String maliciousState = userB.getId().toString();
        
        ResponseEntity<Void> callbackResponse = integrationController.oauthCallback("VALID_CODE", maliciousState);
        
        assertEquals(302, callbackResponse.getStatusCode().value());
        assertTrue(callbackResponse.getHeaders().getLocation().toString().contains("googleError="));

        // Verify NO interaction with GoogleCalendarService
        verify(googleCalendarService, never()).handleOAuthCallback(anyString(), any(UUID.class));
    }

    @Test
    void test4_UnknownState() throws Exception {
        doReturn(null).when(rBucketMock).getAndDelete();
        ResponseEntity<Void> callbackResponse = integrationController.oauthCallback("VALID_CODE", "random_unknown_state_123");
        assertEquals(302, callbackResponse.getStatusCode().value());
        assertTrue(callbackResponse.getHeaders().getLocation().toString().contains("googleError="));
    }

    @Test
    void test5_ExpiredState() throws Exception {
        // Simulate expired state by returning null
        doReturn(null).when(rBucketMock).getAndDelete();
        
        String state = "expired_state_test";

        ResponseEntity<Void> callbackResponse = integrationController.oauthCallback("VALID_CODE", state);
        assertEquals(302, callbackResponse.getStatusCode().value());
        assertTrue(callbackResponse.getHeaders().getLocation().toString().contains("googleError="));
    }

    @Test
    void test6_ReplayAttack() throws Exception {
        String state = "mock_valid_state";

        // First use - succeeds (returns userA)
        doReturn(userA.getId()).when(rBucketMock).getAndDelete();
        ResponseEntity<Void> firstResponse = integrationController.oauthCallback("CODE_1", state);
        assertEquals(302, firstResponse.getStatusCode().value());
        assertTrue(firstResponse.getHeaders().getLocation().toString().contains("googleConnected=true"));

        // Second use (Replay) - fails (returns null)
        doReturn(null).when(rBucketMock).getAndDelete();
        ResponseEntity<Void> secondResponse = integrationController.oauthCallback("CODE_2", state);
        assertEquals(302, secondResponse.getStatusCode().value());
        assertTrue(secondResponse.getHeaders().getLocation().toString().contains("googleError="));

        // Exchange should only be called once
        verify(googleCalendarService, times(1)).handleOAuthCallback(anyString(), eq(userA.getId()));
    }

    @Test
    void test7_ConcurrentReplay() throws Exception {
        ResponseEntity<Map<String, String>> response = integrationController.getAuthUrl();
        String url = response.getBody().get("url");
        String state = url.substring(url.indexOf("state=") + 6);

        // Ensure mock only returns UUID once to simulate atomic getAndDelete
        doReturn(userA.getId(), (UUID) null, (UUID) null, (UUID) null).when(rBucketMock).getAndDelete();

        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    ResponseEntity<Void> res = integrationController.oauthCallback("CODE", state);
                    if (res.getHeaders().getLocation().toString().contains("googleConnected=true")) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    // Ignore
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        latch.countDown(); // Start all threads at once
        doneLatch.await(); // Wait for completion

        // Only ONE request should ever succeed due to getAndDelete atomicity
        assertEquals(1, successCount.get());
        verify(googleCalendarService, times(1)).handleOAuthCallback(anyString(), any(UUID.class));
    }

    @Test
    void test8_MissingState() throws Exception {
        ResponseEntity<Void> callbackResponse = integrationController.oauthCallback("VALID_CODE", null);
        assertEquals(302, callbackResponse.getStatusCode().value());
        assertTrue(callbackResponse.getHeaders().getLocation().toString().contains("googleError="));
    }

    @Test
    void test9_InvalidMalformedState() throws Exception {
        ResponseEntity<Void> callbackResponse = integrationController.oauthCallback("VALID_CODE", "   ");
        assertEquals(302, callbackResponse.getStatusCode().value());
        assertTrue(callbackResponse.getHeaders().getLocation().toString().contains("googleError="));
    }

    @Test
    void test10_NoTokenPersistenceOnInvalidState() throws Exception {
        doReturn(null).when(rBucketMock).getAndDelete();
        integrationController.oauthCallback("VALID_CODE", "invalid_state");
        verify(googleCalendarService, never()).handleOAuthCallback(anyString(), any(UUID.class));
    }

    @Test
    void test11_TenantUserBinding() throws Exception {
        ResponseEntity<Map<String, String>> response = integrationController.getAuthUrl();
        String url = response.getBody().get("url");
        String state = url.substring(url.indexOf("state=") + 6);

        // Even if attacker tries to switch session, the state belongs to User A
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("userb@example.com", null, null));
        
        integrationController.oauthCallback("CODE", state);
        
        // It should resolve to User A
        verify(googleCalendarService).handleOAuthCallback(anyString(), eq(userA.getId()));
        verify(googleCalendarService, never()).handleOAuthCallback(anyString(), eq(userB.getId()));
    }
}
