package com.chatcrmlite.backend.repositories.email;

import com.chatcrmlite.backend.models.CustomEmail;
import com.chatcrmlite.backend.models.email.EmailCampaignAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface EmailCampaignAuditLogRepository extends JpaRepository<EmailCampaignAuditLog, UUID> {
    List<EmailCampaignAuditLog> findByCampaignOrderByCreatedAtDesc(CustomEmail campaign);
}
