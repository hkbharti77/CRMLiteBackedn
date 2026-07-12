package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.PlatformTicket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PlatformTicketRepository extends JpaRepository<PlatformTicket, String> {
    List<PlatformTicket> findByTenantIdOrderByCreatedAtDesc(String tenantId);
    List<PlatformTicket> findAllByOrderByCreatedAtDesc();
}
