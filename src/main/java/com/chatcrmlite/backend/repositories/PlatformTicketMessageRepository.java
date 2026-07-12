package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.PlatformTicketMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PlatformTicketMessageRepository extends JpaRepository<PlatformTicketMessage, String> {
    List<PlatformTicketMessage> findByTicketIdOrderByCreatedAtAsc(String ticketId);
}
