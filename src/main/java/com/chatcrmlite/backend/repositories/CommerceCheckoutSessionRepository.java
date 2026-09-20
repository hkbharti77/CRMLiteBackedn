package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.CommerceCheckoutSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CommerceCheckoutSessionRepository extends JpaRepository<CommerceCheckoutSession, UUID> {

    @Query("SELECT s FROM CommerceCheckoutSession s WHERE s.tenant.id = :tenantId AND s.customerWaId = :customerWaId AND s.checkoutStep IN ('AWAITING_ADDRESS', 'AWAITING_PAYMENT_CHOICE', 'AWAITING_PAYMENT')")
    Optional<CommerceCheckoutSession> findActiveSession(@Param("tenantId") UUID tenantId, @Param("customerWaId") String customerWaId);

    Optional<CommerceCheckoutSession> findByOrderId(UUID orderId);

    @Query("SELECT s FROM CommerceCheckoutSession s WHERE s.expiresAt < :now AND s.checkoutStep IN ('AWAITING_ADDRESS', 'AWAITING_PAYMENT_CHOICE', 'AWAITING_PAYMENT')")
    List<CommerceCheckoutSession> findAllExpiredActiveSessions(@Param("now") LocalDateTime now);
}
