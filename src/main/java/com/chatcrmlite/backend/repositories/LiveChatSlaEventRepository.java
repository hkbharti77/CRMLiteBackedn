package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.livechat.LiveChatSlaEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface LiveChatSlaEventRepository extends JpaRepository<LiveChatSlaEvent, UUID> {
    Optional<LiveChatSlaEvent> findByQueueIdAndEscalationType(UUID queueId, String escalationType);
}
