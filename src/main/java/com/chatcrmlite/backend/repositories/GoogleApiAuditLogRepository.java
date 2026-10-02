package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.google.GoogleApiAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface GoogleApiAuditLogRepository extends JpaRepository<GoogleApiAuditLog, UUID> {

    List<GoogleApiAuditLog> findTop50ByUserIdOrderByCreatedAtDesc(UUID userId);

    Page<GoogleApiAuditLog> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    long countByUserIdAndStatus(UUID userId, String status);

    long countByUserIdAndCreatedAtAfter(UUID userId, LocalDateTime since);

    long countByUserIdAndStatusAndCreatedAtAfter(UUID userId, String status, LocalDateTime since);
}
