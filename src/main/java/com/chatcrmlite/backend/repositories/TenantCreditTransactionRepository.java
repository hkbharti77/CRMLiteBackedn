package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.TenantCreditTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface TenantCreditTransactionRepository extends JpaRepository<TenantCreditTransaction, UUID> {
    
    @Query("SELECT t FROM TenantCreditTransaction t WHERE t.tenant.id = :tenantId ORDER BY t.createdAt DESC")
    Page<TenantCreditTransaction> findByTenantId(@Param("tenantId") UUID tenantId, Pageable pageable);

    @Query("SELECT COUNT(t) > 0 FROM TenantCreditTransaction t WHERE t.tenant.id = :tenantId AND t.referenceId = :referenceId")
    boolean existsByTenantIdAndReferenceId(@Param("tenantId") UUID tenantId, @Param("referenceId") String referenceId);

    @Query("SELECT COUNT(t) > 0 FROM TenantCreditTransaction t WHERE t.tenant.id = :tenantId AND t.category = :category")
    boolean existsByTenantIdAndCategory(@Param("tenantId") UUID tenantId, @Param("category") String category);
}
