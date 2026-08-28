package com.chatcrmlite.backend.controllers.platform;

import com.chatcrmlite.backend.config.RateLimitConfig;
import com.chatcrmlite.backend.repositories.PlatformAdminRepository;
import com.chatcrmlite.backend.services.platform.PlatformAdminService;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PlatformAuthControllerTest {

    @Mock
    private PlatformAdminService adminService;

    @Mock
    private PlatformAdminRepository adminRepository;

    @Mock
    private RateLimitConfig rateLimitConfig;

    @InjectMocks
    private PlatformAuthController platformAuthController;

    private Map<String, Bucket> testBuckets;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        testBuckets = new ConcurrentHashMap<>();

        // Mock RateLimitConfig to return a real Bucket with 5 tokens/min
        when(rateLimitConfig.resolveBucket(any(String.class))).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return testBuckets.computeIfAbsent(key, k -> 
                Bucket.builder()
                        .addLimit(Bandwidth.classic(5, Refill.intervally(5, Duration.ofMinutes(1))))
                        .build()
            );
        });
    }

    @Test
    @DisplayName("OTP rate limit: 6th request from same IP returns 429")
    void testRequestOtp_RateLimitExceeded() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.100");

        PlatformAuthController.PlatformOtpRequest body = new PlatformAuthController.PlatformOtpRequest();
        body.setEmail("gyanvaniai@gmail.com");

        when(adminService.requestOtp(eq("gyanvaniai@gmail.com"), any())).thenReturn(Map.of("status", "ok"));

        // First 5 should succeed
        for (int i = 0; i < 5; i++) {
            ResponseEntity<Map<String, Object>> response = platformAuthController.requestOtp(body, request);
            assertEquals(HttpStatus.OK, response.getStatusCode());
        }

        // 6th should fail with 429
        ResponseEntity<Map<String, Object>> response = platformAuthController.requestOtp(body, request);
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertEquals("rate_limited", response.getBody().get("status"));

        verify(adminService, times(5)).requestOtp(eq("gyanvaniai@gmail.com"), any());
    }

    @Test
    @DisplayName("Invalid requests are rate limited")
    void testRequestOtp_InvalidRequestsConsumeRateLimit() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.200");

        PlatformAuthController.PlatformOtpRequest body = new PlatformAuthController.PlatformOtpRequest();
        body.setEmail(""); // Invalid email

        // Send 5 invalid requests
        for (int i = 0; i < 5; i++) {
            ResponseEntity<Map<String, Object>> response = platformAuthController.requestOtp(body, request);
            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        }

        // 6th invalid request should trigger 429
        ResponseEntity<Map<String, Object>> response = platformAuthController.requestOtp(body, request);
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertEquals("rate_limited", response.getBody().get("status"));

        // adminService should not be called at all
        verify(adminService, never()).requestOtp(any(), any());
    }

    @Test
    @DisplayName("Login/verification rate limit")
    void testLogin_RateLimitExceeded() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.150");
        MockHttpServletResponse responseServlet = new MockHttpServletResponse();

        PlatformAuthController.PlatformLoginRequest body = new PlatformAuthController.PlatformLoginRequest();
        body.setEmail("gyanvaniai@gmail.com");
        body.setOtp("123456");

        when(adminService.login(eq("gyanvaniai@gmail.com"), eq("123456"), any(), any()))
                .thenReturn(Map.of("status", "invalid")); // simulate invalid OTP

        // First 5 login attempts
        for (int i = 0; i < 5; i++) {
            ResponseEntity<Map<String, Object>> response = platformAuthController.login(body, request, responseServlet);
            assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        }

        // 6th login attempt should be rate limited
        ResponseEntity<Map<String, Object>> response = platformAuthController.login(body, request, responseServlet);
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        
        verify(adminService, times(5)).login(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Different IP behavior")
    void testRateLimit_DifferentIpsHaveDifferentBuckets() {
        MockHttpServletRequest request1 = new MockHttpServletRequest();
        request1.setRemoteAddr("10.0.0.1");
        
        MockHttpServletRequest request2 = new MockHttpServletRequest();
        request2.setRemoteAddr("10.0.0.2");

        PlatformAuthController.PlatformOtpRequest body = new PlatformAuthController.PlatformOtpRequest();
        body.setEmail("gyanvaniai@gmail.com");

        when(adminService.requestOtp(any(), any())).thenReturn(Map.of("status", "ok"));

        // IP 1 makes 5 requests (exhausts bucket)
        for (int i = 0; i < 5; i++) {
            platformAuthController.requestOtp(body, request1);
        }
        
        // IP 1 6th request fails
        ResponseEntity<Map<String, Object>> response1 = platformAuthController.requestOtp(body, request1);
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response1.getStatusCode());

        // IP 2 makes 1st request, succeeds because it has its own bucket
        ResponseEntity<Map<String, Object>> response2 = platformAuthController.requestOtp(body, request2);
        assertEquals(HttpStatus.OK, response2.getStatusCode());
    }

    @Test
    @DisplayName("Existing platform email restriction works for non-rate-limited")
    void testRequestOtp_UnauthorizedEmail() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");

        PlatformAuthController.PlatformOtpRequest body = new PlatformAuthController.PlatformOtpRequest();
        body.setEmail("attacker@evil.com");

        // Simulate admin service rejecting the unauthorized email
        when(adminService.requestOtp(eq("attacker@evil.com"), any())).thenReturn(Map.of("status", "invalid"));

        ResponseEntity<Map<String, Object>> response = platformAuthController.requestOtp(body, request);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }
}
