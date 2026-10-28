package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.KnowledgeDocument;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, UUID> {

    @Query("SELECT d FROM KnowledgeDocument d WHERE d.tenant.id = :tenantId AND d.status = :status ORDER BY d.createdAt DESC")
    List<KnowledgeDocument> findByTenantIdAndStatusOrderByCreatedAtDesc(@Param("tenantId") UUID tenantId, @Param("status") String status);

    @Query("SELECT d FROM KnowledgeDocument d WHERE d.tenant.id = :tenantId ORDER BY d.createdAt DESC")
    List<KnowledgeDocument> findByTenantIdOrderByCreatedAtDesc(@Param("tenantId") UUID tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM KnowledgeDocument d WHERE d.id = :id")
    Optional<KnowledgeDocument> findByIdForUpdate(@Param("id") UUID id);

    @Query("SELECT COUNT(d) FROM KnowledgeDocument d WHERE d.tenant.id = :tenantId AND d.status = :status")
    long countByTenantIdAndStatus(@Param("tenantId") UUID tenantId, @Param("status") String status);

    @Query("SELECT d FROM KnowledgeDocument d WHERE d.id = :id AND d.tenant.id = :tenantId")
    Optional<KnowledgeDocument> findByIdAndTenantId(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

    @Modifying
    @Query("UPDATE KnowledgeDocument d SET d.activeVersion = :nextVersion, d.updatedAt = CURRENT_TIMESTAMP WHERE d.id = :id AND d.activeVersion = :expectedVersion")
    int updateActiveVersion(@Param("id") UUID id, @Param("nextVersion") Integer nextVersion, @Param("expectedVersion") Integer expectedVersion);

    @Modifying
    @Query("UPDATE KnowledgeDocument d SET d.status = :status, d.updatedAt = CURRENT_TIMESTAMP WHERE d.id = :id AND d.tenant.id = :tenantId")
    int updateStatus(@Param("id") UUID id, @Param("tenantId") UUID tenantId, @Param("status") String status);
}
