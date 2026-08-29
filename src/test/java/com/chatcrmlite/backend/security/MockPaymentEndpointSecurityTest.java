package com.chatcrmlite.backend.security;

import com.chatcrmlite.backend.controllers.dev.DevMockPaymentController;
import com.chatcrmlite.backend.models.*;
import com.chatcrmlite.backend.models.BillingTransaction.PaymentGateway;
import com.chatcrmlite.backend.models.BillingTransaction.TransactionStatus;
import com.chatcrmlite.backend.models.TenantSubscription.BillingCycle;
import com.chatcrmlite.backend.models.TenantSubscription.SubscriptionStatus;
import com.chatcrmlite.backend.repositories.*;
import com.chatcrmlite.backend.services.billing.SubscriptionBillingService;
import com.chatcrmlite.backend.services.payment.RazorpayPaymentService;
import com.chatcrmlite.backend.services.payment.StripePaymentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class MockPaymentEndpointSecurityTest {

    @Nested
    @DisplayName("Profile-Based Endpoint Registration Tests")
    class ProfileRegistrationUnitTests {

        private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
                .withBean(UserRepository.class, () -> mock(UserRepository.class))
                .withBean(BillingTransactionRepository.class, () -> mock(BillingTransactionRepository.class))
                .withBean(SubscriptionBillingService.class, () -> mock(SubscriptionBillingService.class))
                .withUserConfiguration(DevMockPaymentController.class);

        @Test
        @DisplayName("Security Requirement: In production profile, DevMockPaymentController bean is NOT registered")
        void testDevMockPaymentController_ProductionProfile_NotRegistered() {
            contextRunner.withPropertyValues("spring.profiles.active=prod")
                    .run(context -> {
                        assertThat(context).doesNotHaveBean(DevMockPaymentController.class);
                    });
        }

        @Test
        @DisplayName("Security Requirement: In default profile (no active profile), DevMockPaymentController bean is NOT registered")
        void testDevMockPaymentController_DefaultProfile_NotRegistered() {
            contextRunner.withPropertyValues("spring.profiles.active=default")
                    .run(context -> {
                        assertThat(context).doesNotHaveBean(DevMockPaymentController.class);
                    });
        }

        @Test
        @DisplayName("Security Requirement: In staging profile, DevMockPaymentController bean is NOT registered")
        void testDevMockPaymentController_StagingProfile_NotRegistered() {
            contextRunner.withPropertyValues("spring.profiles.active=staging")
                    .run(context -> {
                        assertThat(context).doesNotHaveBean(DevMockPaymentController.class);
                    });
        }

        @Test
        @DisplayName("Dev Profile: DevMockPaymentController bean is registered in dev profile")
        void testDevMockPaymentController_DevProfile_Registered() {
            contextRunner.withPropertyValues("spring.profiles.active=dev")
                    .run(context -> {
                        assertThat(context).hasSingleBean(DevMockPaymentController.class);
                    });
        }

        @Test
        @DisplayName("Test Profile: DevMockPaymentController bean is registered in test profile")
        void testDevMockPaymentController_TestProfile_Registered() {
            contextRunner.withPropertyValues("spring.profiles.active=test")
                    .run(context -> {
                        assertThat(context).hasSingleBean(DevMockPaymentController.class);
                    });
        }

        @Test
        @DisplayName("Local Profile: DevMockPaymentController bean is registered in local profile")
        void testDevMockPaymentController_LocalProfile_Registered() {
            contextRunner.withPropertyValues("spring.profiles.active=local")
                    .run(context -> {
                        assertThat(context).hasSingleBean(DevMockPaymentController.class);
                    });
        }
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    @Transactional
    @DisplayName("Mock Payment Security and Isolation Integration Tests")
    class MockPaymentIntegrationSecurityTests {

        @Autowired private MockMvc mockMvc;
        @Autowired private ObjectMapper objectMapper;
        @Autowired private UserRepository userRepository;
        @Autowired private TenantRepository tenantRepository;
        @Autowired private SubscriptionPlanRepository subscriptionPlanRepository;
        @Autowired private TenantSubscriptionRepository tenantSubscriptionRepository;
        @Autowired private BillingTransactionRepository billingTransactionRepository;
        @Autowired private UserSessionRepository sessionRepository;
        @Autowired private JwtUtils jwtUtils;
        @Autowired private PasswordEncoder passwordEncoder;
        @Autowired private jakarta.persistence.EntityManager entityManager;

        @MockBean private StripePaymentService stripePaymentService;
        @MockBean private RazorpayPaymentService razorpayPaymentService;

        private User tenantAUser;
        private String tenantAToken;
        private Tenant tenantA;
        private Tenant tenantB;

        @BeforeEach
        void setUp() {
            sessionRepository.deleteAll();
            userRepository.deleteAll();
            tenantSubscriptionRepository.deleteAll();
            tenantRepository.deleteAll();

            SubscriptionPlan freePlan = subscriptionPlanRepository.findById("FREE").orElseGet(() -> {
                SubscriptionPlan plan = new SubscriptionPlan("FREE", "Free Pack", BigDecimal.ZERO, BigDecimal.ZERO, 1, 100, 15, 10, 500, false, false, false);
                return subscriptionPlanRepository.save(plan);
            });

            tenantA = Tenant.builder().businessName("Tenant A").businessType("retail").build();
            tenantB = Tenant.builder().businessName("Tenant B").businessType("retail").build();
            tenantA = tenantRepository.save(tenantA);
            tenantB = tenantRepository.save(tenantB);

            TenantSubscription subA = new TenantSubscription();
            subA.setTenant(tenantA);
            subA.setPlan(freePlan);
            subA.setBillingCycle(BillingCycle.MONTHLY);
            subA.setStatus(SubscriptionStatus.ACTIVE);
            subA.setCurrentPeriodStart(LocalDateTime.now());
            subA.setCurrentPeriodEnd(LocalDateTime.now().plusMonths(1));
            tenantSubscriptionRepository.save(subA);

            TenantSubscription subB = new TenantSubscription();
            subB.setTenant(tenantB);
            subB.setPlan(freePlan);
            subB.setBillingCycle(BillingCycle.MONTHLY);
            subB.setStatus(SubscriptionStatus.ACTIVE);
            subB.setCurrentPeriodStart(LocalDateTime.now());
            subB.setCurrentPeriodEnd(LocalDateTime.now().plusMonths(1));
            tenantSubscriptionRepository.save(subB);

            tenantAUser = User.builder()
                    .email("admin@tenant-a.com")
                    .password(passwordEncoder.encode("Password123!"))
                    .role(User.Role.ADMIN)
                    .accountStatus(User.AccountStatus.ACTIVE)
                    .tenant(tenantA)
                    .build();
            tenantAUser = userRepository.save(tenantAUser);

            String sessionToken = "test-session-a-" + UUID.randomUUID();
            UserSession session = UserSession.builder()
                    .tokenId(sessionToken)
                    .user(tenantAUser)
                    .status("ACTIVE")
                    .createdAt(LocalDateTime.now())
                    .expiresAt(LocalDateTime.now().plusDays(1))
                    .build();
            sessionRepository.save(session);

            tenantAToken = jwtUtils.generateJwtToken(tenantAUser.getEmail(), sessionToken);
        }

        @Test
        @DisplayName("Security Requirement: Cross-tenant mock success is blocked and does not modify victim subscription")
        void testMockPaymentEndpoint_CrossTenant_Blocked() throws Exception {
            // Transaction belongs to Tenant B
            BillingTransaction transactionB = BillingTransaction.builder()
                    .amount(BigDecimal.valueOf(2999.00))
                    .currency("INR")
                    .status(TransactionStatus.PENDING)
                    .paymentGateway(PaymentGateway.RAZORPAY)
                    .gatewayTransactionId("order_tenant_b_111")
                    .tenant(tenantB)
                    .build();
            billingTransactionRepository.save(transactionB);

            Map<String, String> request = new HashMap<>();
            request.put("orderId", "order_tenant_b_111");

            // User from Tenant A tries to trigger mock success on Tenant B's order
            mockMvc.perform(post("/api/v1/billing/mock-success")
                            .header("Authorization", "Bearer " + tenantAToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().is4xxClientError());

            // Verify Tenant B's subscription remains unchanged as FREE
            com.chatcrmlite.backend.security.TenantContext.clear();
            com.chatcrmlite.backend.security.TenantContext.setAdminMode(true);
            try {
                if (entityManager.unwrap(org.hibernate.Session.class).getEnabledFilter("tenantFilter") != null) {
                    entityManager.unwrap(org.hibernate.Session.class).disableFilter("tenantFilter");
                }
                TenantSubscription currentSubB = tenantSubscriptionRepository.findByTenantId(tenantB.getId()).orElseThrow();
                assertEquals("FREE", currentSubB.getPlan().getId(), "Tenant B subscription must remain FREE");
            } finally {
                com.chatcrmlite.backend.security.TenantContext.clear();
            }
        }

        @Test
        @DisplayName("Security Requirement: Unauthenticated mock success request is rejected")
        void testMockPaymentEndpoint_Unauthenticated_Blocked() throws Exception {
            Map<String, String> request = new HashMap<>();
            request.put("orderId", "order_any_123");

            mockMvc.perform(post("/api/v1/billing/mock-success")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }
    }
}
