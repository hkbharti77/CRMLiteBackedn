package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.ProcessedMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Repository
public interface ProcessedMessageRepository extends JpaRepository<ProcessedMessage, Long> {

    boolean existsByMessageId(String messageId);

    /**
     * Purge old idempotency records after 30 days to keep the table lean.
     * Called by a scheduled cleanup job.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM ProcessedMessage p WHERE p.processedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
