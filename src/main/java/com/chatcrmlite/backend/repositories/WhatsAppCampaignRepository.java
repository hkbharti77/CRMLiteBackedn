package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.WhatsAppCampaign;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface WhatsAppCampaignRepository extends JpaRepository<WhatsAppCampaign, UUID> {
    Page<WhatsAppCampaign> findByOwner(User owner, Pageable pageable);
    List<WhatsAppCampaign> findByStatusAndScheduledAtBefore(WhatsAppCampaign.Status status, LocalDateTime now);
    long countByOwnerAndStatus(User owner, WhatsAppCampaign.Status status);
}
