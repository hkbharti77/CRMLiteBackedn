package com.chatcrmlite.backend.repositories.payments;

import com.chatcrmlite.backend.models.enums.PaymentTransactionStatus;
import com.chatcrmlite.backend.models.payments.PaymentTransaction;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, UUID> {

    @Query("SELECT t FROM PaymentTransaction t WHERE t.id = :id AND t.tenant.id = :tenantId")
    Optional<PaymentTransaction> findByIdAndTenantId(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM PaymentTransaction t WHERE t.id = :id AND t.tenant.id = :tenantId")
    Optional<PaymentTransaction> findByIdAndTenantIdForUpdate(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

    @Query("SELECT t FROM PaymentTransaction t WHERE t.idempotencyKey = :idempotencyKey AND t.tenant.id = :tenantId")
    Optional<PaymentTransaction> findByIdempotencyKeyAndTenantId(@Param("idempotencyKey") String idempotencyKey, @Param("tenantId") UUID tenantId);

    @Query("SELECT t FROM PaymentTransaction t WHERE t.providerOrderId = :providerOrderId AND t.tenant.id = :tenantId")
    Optional<PaymentTransaction> findByProviderOrderIdAndTenantId(@Param("providerOrderId") String providerOrderId, @Param("tenantId") UUID tenantId);

    @Query("SELECT t FROM PaymentTransaction t WHERE t.providerPaymentId = :providerPaymentId AND t.tenant.id = :tenantId")
    Optional<PaymentTransaction> findByProviderPaymentIdAndTenantId(@Param("providerPaymentId") String providerPaymentId, @Param("tenantId") UUID tenantId);

    @Query("SELECT t FROM PaymentTransaction t WHERE t.order.id = :orderId AND t.tenant.id = :tenantId ORDER BY t.createdAt DESC")
    List<PaymentTransaction> findAllByOrderIdAndTenantId(@Param("orderId") UUID orderId, @Param("tenantId") UUID tenantId);

    @Query("SELECT DISTINCT t FROM PaymentTransaction t LEFT JOIN FETCH t.order LEFT JOIN FETCH t.paymentIntegration WHERE t.status IN (:statuses) AND t.createdAt <= :cutoffTime ORDER BY t.createdAt ASC")
    List<PaymentTransaction> findUnresolvedTransactionsForReconciliation(
        @Param("statuses") List<PaymentTransactionStatus> statuses,
        @Param("cutoffTime") Instant cutoffTime
    );
}
