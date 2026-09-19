package com.chatcrmlite.backend.repositories.payments;

import com.chatcrmlite.backend.models.payments.PaymentAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PaymentAuditLogRepository extends JpaRepository<PaymentAuditLog, UUID> {

    @Query("SELECT l FROM PaymentAuditLog l WHERE l.orderId = :orderId AND l.tenant.id = :tenantId ORDER BY l.createdAt ASC")
    List<PaymentAuditLog> findAllByOrderIdAndTenantId(@Param("orderId") UUID orderId, @Param("tenantId") UUID tenantId);

    @Query("SELECT l FROM PaymentAuditLog l WHERE l.transactionId = :transactionId AND l.tenant.id = :tenantId ORDER BY l.createdAt ASC")
    List<PaymentAuditLog> findAllByTransactionIdAndTenantId(@Param("transactionId") UUID transactionId, @Param("tenantId") UUID tenantId);
}
