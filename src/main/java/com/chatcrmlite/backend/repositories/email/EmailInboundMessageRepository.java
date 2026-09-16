package com.chatcrmlite.backend.repositories.email;

import com.chatcrmlite.backend.models.email.EmailInboundMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmailInboundMessageRepository extends JpaRepository<EmailInboundMessage, UUID> {

    Optional<EmailInboundMessage> findByProviderAndProviderMessageId(String provider, String providerMessageId);

    long countByCampaignId(UUID campaignId);

    @Query("SELECT m FROM EmailInboundMessage m WHERE m.campaignId = :campaignId ORDER BY m.receivedAt DESC")
    List<EmailInboundMessage> findByCampaignIdOrderByReceivedAtDesc(@Param("campaignId") UUID campaignId);

    @Query("SELECT m FROM EmailInboundMessage m WHERE m.tenant.id = :tenantId AND m.campaignId = :campaignId ORDER BY m.receivedAt DESC")
    List<EmailInboundMessage> findByTenantIdAndCampaignIdOrderByReceivedAtDesc(@Param("tenantId") UUID tenantId, @Param("campaignId") UUID campaignId);

    @Query("SELECT m FROM EmailInboundMessage m WHERE m.tenant.id = :tenantId AND m.campaignRecipientId = :campaignRecipientId ORDER BY m.receivedAt DESC")
    List<EmailInboundMessage> findByTenantIdAndCampaignRecipientIdOrderByReceivedAtDesc(@Param("tenantId") UUID tenantId, @Param("campaignRecipientId") UUID campaignRecipientId);

    @Query("SELECT m FROM EmailInboundMessage m WHERE m.tenant.id = :tenantId AND m.fromEmail = :fromEmail ORDER BY m.receivedAt DESC")
    List<EmailInboundMessage> findByTenantIdAndFromEmailOrderByReceivedAtDesc(@Param("tenantId") UUID tenantId, @Param("fromEmail") String fromEmail);
}
