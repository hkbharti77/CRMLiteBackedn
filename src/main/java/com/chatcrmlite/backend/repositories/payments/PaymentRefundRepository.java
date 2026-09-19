package com.chatcrmlite.backend.repositories.payments;

import com.chatcrmlite.backend.models.payments.PaymentRefund;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentRefundRepository extends JpaRepository<PaymentRefund, UUID> {

    @Query("SELECT r FROM PaymentRefund r WHERE r.id = :id AND r.tenant.id = :tenantId")
    Optional<PaymentRefund> findByIdAndTenantId(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

    @Query("SELECT r FROM PaymentRefund r WHERE r.transaction.id = :transactionId AND r.tenant.id = :tenantId ORDER BY r.createdAt DESC")
    List<PaymentRefund> findAllByTransactionIdAndTenantId(@Param("transactionId") UUID transactionId, @Param("tenantId") UUID tenantId);

    @Query("SELECT r FROM PaymentRefund r WHERE r.order.id = :orderId AND r.tenant.id = :tenantId ORDER BY r.createdAt DESC")
    List<PaymentRefund> findAllByOrderIdAndTenantId(@Param("orderId") UUID orderId, @Param("tenantId") UUID tenantId);

    @Query("SELECT COALESCE(SUM(r.amountMinor), 0) FROM PaymentRefund r WHERE r.transaction.id = :transactionId AND r.status = 'PROCESSED'")
    Long calculateTotalProcessedRefundAmount(@Param("transactionId") UUID transactionId);

    @Query("SELECT COALESCE(SUM(r.amountMinor), 0) FROM PaymentRefund r WHERE r.transaction.id = :transactionId AND r.status IN ('INITIATED', 'PROCESSED')")
    Long calculateTotalActiveRefundAmount(@Param("transactionId") UUID transactionId);
}
