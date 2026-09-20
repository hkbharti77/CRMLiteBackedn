package com.chatcrmlite.backend.services.whatsapp.checkout;

import com.chatcrmlite.backend.models.CommerceCheckoutSession;
import com.chatcrmlite.backend.models.CommerceOrder;
import com.chatcrmlite.backend.repositories.CommerceCheckoutSessionRepository;
import com.chatcrmlite.backend.repositories.CommerceOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CheckoutSessionExpiryJob {

    private final CommerceCheckoutSessionRepository checkoutSessionRepository;
    private final CommerceOrderRepository orderRepository;

    @Scheduled(fixedDelay = 60000) // Runs every 60 seconds
    @Transactional
    public void cleanupExpiredCheckoutSessions() {
        LocalDateTime now = LocalDateTime.now();
        List<CommerceCheckoutSession> expired = checkoutSessionRepository.findAllExpiredActiveSessions(now);

        if (expired.isEmpty()) {
            return;
        }

        log.info("⏰ Found {} expired checkout sessions to clean up", expired.size());
        for (CommerceCheckoutSession session : expired) {
            try {
                session.setCheckoutStep("EXPIRED");
                checkoutSessionRepository.save(session);

                CommerceOrder order = session.getOrder();
                if (order != null && "UNPAID".equalsIgnoreCase(order.getPaymentStatus()) 
                        && ("AWAITING_CUSTOMER_DETAILS".equals(order.getCheckoutStatus()) 
                            || "DETAILS_COLLECTED".equals(order.getCheckoutStatus()))) {
                    order.setCheckoutStatus("EXPIRED");
                    order.setStatus("CANCELLED");
                    orderRepository.save(order);
                }
                log.info("🧹 Expired session {} and updated order {}", session.getId(), order != null ? order.getId() : "null");
            } catch (Exception e) {
                log.error("Failed to expire checkout session {}: {}", session.getId(), e.getMessage());
            }
        }
    }
}
