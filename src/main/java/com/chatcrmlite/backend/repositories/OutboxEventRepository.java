package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.livechat.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {
    
    @Query("SELECT e FROM OutboxEvent e WHERE e.status = 'PENDING' AND e.attemptCount < :maxAttempts ORDER BY e.createdAt ASC")
    List<OutboxEvent> findPendingEvents(@Param("maxAttempts") int maxAttempts);
}
