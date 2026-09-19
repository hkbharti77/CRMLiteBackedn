package com.chatcrmlite.backend.repositories.payments;

import com.chatcrmlite.backend.models.payments.PaymentOutboxEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface PaymentOutboxEventRepository extends JpaRepository<PaymentOutboxEvent, UUID> {

    @Query(value = """
        SELECT * FROM payment_outbox_events
        WHERE status = 'PENDING' AND next_retry_at <= :now
        ORDER BY next_retry_at ASC
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<PaymentOutboxEvent> pollPendingEventsWithLock(@Param("now") Instant now, @Param("limit") int limit);

    @Modifying
    @Query("""
        UPDATE PaymentOutboxEvent e
        SET e.status = 'PROCESSING', e.lockedAt = :now, e.lockedBy = :instanceId, e.lastAttemptAt = :now
        WHERE e.id IN (:ids)
        """)
    int markEventsAsProcessing(@Param("ids") List<UUID> ids, @Param("now") Instant now, @Param("instanceId") String instanceId);

    @Modifying
    @Query("""
        UPDATE PaymentOutboxEvent e
        SET e.status = 'PENDING', e.lockedAt = null, e.lockedBy = null
        WHERE e.status = 'PROCESSING' AND e.lockedAt <= :staleCutoff
        """)
    int recoverStaleLocks(@Param("staleCutoff") Instant staleCutoff);

    @Query("SELECT e FROM PaymentOutboxEvent e WHERE e.order.id = :orderId AND e.tenant.id = :tenantId ORDER BY e.createdAt DESC")
    List<PaymentOutboxEvent> findAllByOrderIdAndTenantId(@Param("orderId") UUID orderId, @Param("tenantId") UUID tenantId);
}
