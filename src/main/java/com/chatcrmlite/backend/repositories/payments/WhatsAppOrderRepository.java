package com.chatcrmlite.backend.repositories.payments;

import com.chatcrmlite.backend.models.enums.WhatsAppOrderPaymentStatus;
import com.chatcrmlite.backend.models.enums.WhatsAppOrderStatus;
import com.chatcrmlite.backend.models.payments.WhatsAppOrder;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface WhatsAppOrderRepository extends JpaRepository<WhatsAppOrder, UUID> {

    @Query("SELECT o FROM WhatsAppOrder o WHERE o.referenceId = :referenceId AND o.tenant.id = :tenantId")
    Optional<WhatsAppOrder> findByReferenceIdAndTenantId(@Param("referenceId") String referenceId, @Param("tenantId") UUID tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM WhatsAppOrder o WHERE o.id = :id AND o.tenant.id = :tenantId")
    Optional<WhatsAppOrder> findByIdAndTenantIdForUpdate(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM WhatsAppOrder o WHERE o.referenceId = :referenceId AND o.tenant.id = :tenantId")
    Optional<WhatsAppOrder> findByReferenceIdAndTenantIdForUpdate(@Param("referenceId") String referenceId, @Param("tenantId") UUID tenantId);

    @Query("SELECT o FROM WhatsAppOrder o WHERE o.id = :id AND o.tenant.id = :tenantId")
    Optional<WhatsAppOrder> findByIdAndTenantId(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

    @Query("SELECT o FROM WhatsAppOrder o WHERE o.tenant.id = :tenantId ORDER BY o.createdAt DESC")
    Page<WhatsAppOrder> findAllByTenantId(@Param("tenantId") UUID tenantId, Pageable pageable);

    @Query("SELECT o FROM WhatsAppOrder o WHERE o.tenant.id = :tenantId AND o.paymentStatus = :paymentStatus ORDER BY o.createdAt DESC")
    Page<WhatsAppOrder> findAllByTenantIdAndPaymentStatus(@Param("tenantId") UUID tenantId, @Param("paymentStatus") WhatsAppOrderPaymentStatus paymentStatus, Pageable pageable);

    @Query("SELECT o FROM WhatsAppOrder o WHERE o.tenant.id = :tenantId AND o.customerWaId = :customerWaId ORDER BY o.createdAt DESC")
    Page<WhatsAppOrder> findAllByTenantIdAndCustomerWaId(@Param("tenantId") UUID tenantId, @Param("customerWaId") String customerWaId, Pageable pageable);
}
