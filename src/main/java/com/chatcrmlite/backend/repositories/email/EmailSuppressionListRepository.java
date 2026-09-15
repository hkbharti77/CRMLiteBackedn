package com.chatcrmlite.backend.repositories.email;

import com.chatcrmlite.backend.models.email.EmailSuppressionList;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmailSuppressionListRepository extends JpaRepository<EmailSuppressionList, UUID> {
    @Query("SELECT s FROM EmailSuppressionList s WHERE s.tenant.id = :tenantId AND s.email = :email")
    Optional<EmailSuppressionList> findByTenantIdAndEmail(@Param("tenantId") UUID tenantId, @Param("email") String email);

    @Query("SELECT CASE WHEN COUNT(s) > 0 THEN true ELSE false END FROM EmailSuppressionList s WHERE s.tenant.id = :tenantId AND s.email = :email")
    boolean existsByTenantIdAndEmail(@Param("tenantId") UUID tenantId, @Param("email") String email);

    @Query("SELECT s FROM EmailSuppressionList s WHERE s.tenant.id = :tenantId ORDER BY s.createdAt DESC")
    List<EmailSuppressionList> findByTenantIdOrderByCreatedAtDesc(@Param("tenantId") UUID tenantId);
}
