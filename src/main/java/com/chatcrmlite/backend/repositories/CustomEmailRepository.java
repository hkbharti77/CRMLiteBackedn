package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.CustomEmail;
import com.chatcrmlite.backend.models.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CustomEmailRepository extends JpaRepository<CustomEmail, UUID> {

    Page<CustomEmail> findAllByOwnerOrderByCreatedAtDesc(User owner, Pageable pageable);

    List<CustomEmail> findAllByOwnerOrderByCreatedAtDesc(User owner);

    long countByOwner(User owner);

    // ── Tenant-Wide Methods ──
    Page<CustomEmail> findAllByOrderByCreatedAtDesc(Pageable pageable);
    List<CustomEmail> findAllByOrderByCreatedAtDesc();

    @org.springframework.data.jpa.repository.Query("SELECT c FROM CustomEmail c WHERE c.owner.tenant.id = :tenantId ORDER BY c.createdAt DESC")
    Page<CustomEmail> findByTenantIdOrderByCreatedAtDesc(@org.springframework.data.repository.query.Param("tenantId") UUID tenantId, Pageable pageable);

    @org.springframework.data.jpa.repository.Query("SELECT COALESCE(SUM(e.totalSent), 0) FROM CustomEmail e WHERE e.owner.tenant.id = :tenantId AND e.createdAt >= :since")
    long countSentEmailsSince(@org.springframework.data.repository.query.Param("tenantId") UUID tenantId, @org.springframework.data.repository.query.Param("since") java.time.LocalDateTime since);
}

