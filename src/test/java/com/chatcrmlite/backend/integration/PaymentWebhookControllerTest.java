package com.chatcrmlite.backend.integration;

import com.chatcrmlite.backend.models.*;
import com.chatcrmlite.backend.models.BillingTransaction.PaymentGateway;
import com.chatcrmlite.backend.models.BillingTransaction.TransactionStatus;
import com.chatcrmlite.backend.repositories.*;
import com.chatcrmlite.backend.security.JwtUtils;
import com.chatcrmlite.backend.services.payment.RazorpayPaymentService;
import com.chatcrmlite.backend.services.payment.StripePaymentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
public class PaymentWebhookControllerTest {

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

    @MockBean private StripePaymentService stripePaymentService;
    @MockBean private RazorpayPaymentService razorpayPaymentService;

    private User testUser;
    private String authToken;
    private SubscriptionPlan freePlan;
    private SubscriptionPlan proPlan;

    @BeforeEach
    void setUp() {
        // Setup default plans
        freePlan = subscriptionPlanRepository.findById("FREE").orElseGet(() -> {
            SubscriptionPlan plan = new SubscriptionPlan("FREE", "Free Pack", BigDecimal.ZERO, BigDecimal.ZERO, 1, 100, 15, 10, 500, false, false, false);
            return subscriptionPlanRepository.save(plan);
        });
        proPlan = subscriptionPlanRepository.findById("PRO").orElseGet(() -> {
            SubscriptionPlan plan = new SubscriptionPlan("PRO", "Pro Pack", BigDecimal.valueOf(2999), BigDecimal.valueOf(28790), 10, 1000000, 1000000, 1000000, 25000, true, true, true);
            return subscriptionPlanRepository.save(plan);
        });

        Tenant tenant = Tenant.builder()
                .businessName("Test Billing Business")
                .businessType("GENERAL")
                .businessSubType("GENERAL")
                .build();
        tenant = tenantRepository.save(tenant);

        testUser = User.builder()
                .email("billing-test@example.com")
                .password(passwordEncoder.encode("testpwd123"))
                .businessName("Test Billing Business")
                .tenant(tenant)
                .build();
        testUser = userRepository.save(testUser);

        UserSession session = UserSession.builder()
                .tokenId("billing-test-session")
                .user(testUser)
                .status("ACTIVE")
                .createdAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusDays(1))
                .build();
        sessionRepository.save(session);

        authToken = jwtUtils.generateJwtToken(testUser.getEmail(), "billing-test-session");
    }

    @Test
    void testGetSubscriptionStatus_DefaultFree() throws Exception {
        mockMvc.perform(get("/api/v1/billing/subscription")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planId", equalTo("FREE")))
                .andExpect(jsonPath("$.limits.employeeLimit", equalTo(1)))
                .andExpect(jsonPath("$.limits.primaryResourceLimit", equalTo(100)));
    }

    @Test
    void testInitiateUpgrade_StripeCheckout() throws Exception {
        when(stripePaymentService.createCheckoutSession(any(), anyString(), anyString(), any(), anyString()))
                .thenReturn("https://checkout.stripe.com/pay/mock_session");

        Map<String, String> request = new HashMap<>();
        request.put("planId", "PRO");
        request.put("billingCycle", "MONTHLY");
        request.put("gateway", "STRIPE");

        mockMvc.perform(post("/api/v1/billing/checkout")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gateway", equalTo("STRIPE")))
                .andExpect(jsonPath("$.checkoutUrl", equalTo("https://checkout.stripe.com/pay/mock_session")));
    }

    @Test
    void testInitiateUpgrade_RazorpayOrder() throws Exception {
        when(razorpayPaymentService.createOrder(any(), anyString(), anyString()))
                .thenReturn("order_mock_razorpay_123");

        Map<String, String> request = new HashMap<>();
        request.put("planId", "PRO");
        request.put("billingCycle", "MONTHLY");
        request.put("gateway", "RAZORPAY");

        mockMvc.perform(post("/api/v1/billing/checkout")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gateway", equalTo("RAZORPAY")))
                .andExpect(jsonPath("$.orderId", equalTo("order_mock_razorpay_123")));
    }

    @Test
    void testMockPaymentSuccessFlow() throws Exception {
        // Pre-create pending order transaction
        BillingTransaction transaction = BillingTransaction.builder()
                .amount(BigDecimal.valueOf(2999.00))
                .currency("INR")
                .status(TransactionStatus.PENDING)
                .paymentGateway(PaymentGateway.RAZORPAY)
                .gatewayTransactionId("order_mock_1122")
                .tenant(testUser.getTenant())
                .build();
        billingTransactionRepository.save(transaction);

        Map<String, String> request = new HashMap<>();
        request.put("orderId", "order_mock_1122");

        mockMvc.perform(post("/api/v1/billing/mock-success")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", containsString("Mock payment processed successfully")))
                .andExpect(jsonPath("$.plan", equalTo("PRO")));

        // Verify status upgraded on GET status
        mockMvc.perform(get("/api/v1/billing/subscription")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planId", equalTo("PRO")))
                .andExpect(jsonPath("$.limits.employeeLimit", equalTo(10)));
    }
}
