package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.livechat.LiveChatQueue;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LiveChatQueueRepository extends JpaRepository<LiveChatQueue, UUID> {

    Optional<LiveChatQueue> findByContactAndTenantAndStatus(Contact contact, Tenant tenant, LiveChatQueue.QueueStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT q FROM LiveChatQueue q WHERE q.tenant = :tenant AND q.status = 'QUEUED' ORDER BY q.queuedAt ASC, q.id ASC")
    List<LiveChatQueue> findQueuedByTenantForUpdate(@Param("tenant") Tenant tenant);

    @Query("SELECT COUNT(q) FROM LiveChatQueue q WHERE q.tenant = :tenant AND q.status = 'QUEUED' AND (q.queuedAt < :queuedAt OR (q.queuedAt = :queuedAt AND q.id < :id))")
    long countQueuedBefore(@Param("tenant") Tenant tenant, @Param("queuedAt") LocalDateTime queuedAt, @Param("id") UUID id);

    @Query("SELECT q FROM LiveChatQueue q WHERE q.status = 'QUEUED' AND q.slaBreached = false AND q.slaExpiresAt <= :now")
    List<LiveChatQueue> findExpiredUnbreachedQueues(@Param("now") LocalDateTime now);

    List<LiveChatQueue> findAllByTenantAndStatusOrderByQueuedAtAsc(Tenant tenant, LiveChatQueue.QueueStatus status);
}
