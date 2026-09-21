package com.chatcrmlite.backend.repositories.flows;

import com.chatcrmlite.backend.models.flows.FlowLifecycleStatus;
import com.chatcrmlite.backend.models.flows.WhatsAppFlow;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WhatsAppFlowRepository extends JpaRepository<WhatsAppFlow, UUID> {

    @Query("SELECT f FROM WhatsAppFlow f LEFT JOIN FETCH f.publishedRevision WHERE f.tenant.id = :tenantId AND f.status <> 'ARCHIVED' ORDER BY f.createdAt DESC")
    List<WhatsAppFlow> findAllActiveByTenantId(@Param("tenantId") UUID tenantId);

    @Query("SELECT f FROM WhatsAppFlow f LEFT JOIN FETCH f.publishedRevision WHERE f.id = :id AND f.tenant.id = :tenantId")
    Optional<WhatsAppFlow> findByIdAndTenantId(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

    @Query("SELECT f FROM WhatsAppFlow f LEFT JOIN FETCH f.publishedRevision WHERE (f.metaFlowId = :metaFlowId OR f.activeMetaFlowId = :metaFlowId) AND f.tenant.id = :tenantId")
    Optional<WhatsAppFlow> findByAnyMetaFlowIdAndTenantId(@Param("metaFlowId") String metaFlowId, @Param("tenantId") UUID tenantId);

    @Query("SELECT f FROM WhatsAppFlow f LEFT JOIN FETCH f.publishedRevision WHERE f.metaFlowId = :metaFlowId AND f.tenant.id = :tenantId")
    Optional<WhatsAppFlow> findByMetaFlowIdAndTenantId(@Param("metaFlowId") String metaFlowId, @Param("tenantId") UUID tenantId);

    Optional<WhatsAppFlow> findByMetaFlowId(String metaFlowId);

    @Query("SELECT f FROM WhatsAppFlow f WHERE f.tenant.id = :tenantId AND f.status = :status")
    List<WhatsAppFlow> findAllByTenantIdAndStatus(@Param("tenantId") UUID tenantId, @Param("status") FlowLifecycleStatus status);

    /**
     * Acquires a pessimistic write lock on the WhatsAppFlow row.
     * Used by {@link com.chatcrmlite.backend.services.whatsapp.flows.FlowPublishWorker}
     * to prevent two concurrent publish jobs for the same logical Flow from both
     * reading the same active Meta container and creating duplicate clones.
     *
     * <p>Must be called inside an active transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM WhatsAppFlow f WHERE f.id = :id")
    Optional<WhatsAppFlow> findByIdForUpdate(@Param("id") UUID id);
}
