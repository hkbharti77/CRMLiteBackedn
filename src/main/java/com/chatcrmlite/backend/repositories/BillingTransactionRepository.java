package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.BillingTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BillingTransactionRepository extends JpaRepository<BillingTransaction, UUID> {

    @Query("SELECT bt FROM BillingTransaction bt WHERE bt.tenant.id = :tenantId ORDER BY bt.createdAt DESC")
    List<BillingTransaction> findByTenantId(@Param("tenantId") UUID tenantId);

    Optional<BillingTransaction> findByGatewayTransactionId(String gatewayTransactionId);
}
