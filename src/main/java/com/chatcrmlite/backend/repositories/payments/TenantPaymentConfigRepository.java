package com.chatcrmlite.backend.repositories.payments;

import com.chatcrmlite.backend.models.enums.PaymentIntegrationStatus;
import com.chatcrmlite.backend.models.enums.PaymentIntegrationType;
import com.chatcrmlite.backend.models.payments.TenantPaymentConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TenantPaymentConfigRepository extends JpaRepository<TenantPaymentConfig, UUID> {

    @Query("SELECT c FROM TenantPaymentConfig c WHERE c.tenant.id = :tenantId")
    List<TenantPaymentConfig> findAllByTenantId(@Param("tenantId") UUID tenantId);

    @Query("SELECT c FROM TenantPaymentConfig c WHERE c.tenant.id = :tenantId AND c.status = :status AND c.isActive = true")
    List<TenantPaymentConfig> findAllByTenantIdAndStatus(@Param("tenantId") UUID tenantId, @Param("status") PaymentIntegrationStatus status);

    @Query("SELECT c FROM TenantPaymentConfig c WHERE c.tenant.id = :tenantId AND c.integrationType = :type AND c.status = 'ACTIVE' AND c.isActive = true")
    Optional<TenantPaymentConfig> findActiveByTenantIdAndType(@Param("tenantId") UUID tenantId, @Param("type") PaymentIntegrationType type);

    @Query("SELECT c FROM TenantPaymentConfig c WHERE c.id = :id AND c.tenant.id = :tenantId")
    Optional<TenantPaymentConfig> findByIdAndTenantId(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

    @Query("SELECT c FROM TenantPaymentConfig c WHERE c.webhookKeyHash = :hash AND c.status = 'ACTIVE' AND c.isActive = true")
    Optional<TenantPaymentConfig> findByWebhookKeyHash(@Param("hash") String hash);
}
