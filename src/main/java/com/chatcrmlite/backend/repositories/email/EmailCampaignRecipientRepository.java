package com.chatcrmlite.backend.repositories.email;

import com.chatcrmlite.backend.models.email.EmailCampaignRecipient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmailCampaignRecipientRepository extends JpaRepository<EmailCampaignRecipient, UUID> {
    Optional<EmailCampaignRecipient> findByTrackingToken(String trackingToken);
    @org.springframework.data.jpa.repository.Query("SELECT CASE WHEN COUNT(r) > 0 THEN true ELSE false END FROM EmailCampaignRecipient r WHERE r.tenant.id = :tenantId AND r.campaignId = :campaignId AND r.email = :email")
    boolean existsByTenantIdAndCampaignIdAndEmail(@org.springframework.data.repository.query.Param("tenantId") UUID tenantId, @org.springframework.data.repository.query.Param("campaignId") UUID campaignId, @org.springframework.data.repository.query.Param("email") String email);

    Optional<EmailCampaignRecipient> findByReplyToken(String replyToken);
    Optional<EmailCampaignRecipient> findByLastMessageId(String lastMessageId);

    long countByCampaignId(UUID campaignId);
    long countByCampaignIdAndDeliveryStatusIn(UUID campaignId, java.util.Collection<EmailCampaignRecipient.DeliveryStatus> statuses);
    long countByCampaignIdAndFirstOpenedAtIsNotNull(UUID campaignId);
    long countByCampaignIdAndFirstClickedAtIsNotNull(UUID campaignId);
    long countByCampaignIdAndUnsubscribedAtIsNotNull(UUID campaignId);
    long countByCampaignIdAndRepliedAtIsNotNull(UUID campaignId);
    long countByCampaignIdAndDeliveryStatus(UUID campaignId, EmailCampaignRecipient.DeliveryStatus status);
    
    org.springframework.data.domain.Page<EmailCampaignRecipient> findByCampaignIdAndDeliveryStatus(UUID campaignId, EmailCampaignRecipient.DeliveryStatus status, org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("UPDATE EmailCampaignRecipient r SET r.replyCount = r.replyCount + 1, r.repliedAt = COALESCE(r.repliedAt, :receivedAt) WHERE r.id = :id AND r.tenant.id = :tenantId")
    int incrementReplyCountAtomic(@org.springframework.data.repository.query.Param("id") UUID id,
                                 @org.springframework.data.repository.query.Param("tenantId") UUID tenantId,
                                 @org.springframework.data.repository.query.Param("receivedAt") java.time.Instant receivedAt);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("UPDATE EmailCampaignRecipient r SET r.firstOpenedAt = :timestamp WHERE r.id = :id AND r.firstOpenedAt IS NULL")
    int updateFirstOpenedAt(@org.springframework.data.repository.query.Param("id") UUID id, @org.springframework.data.repository.query.Param("timestamp") java.time.LocalDateTime timestamp);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("UPDATE EmailCampaignRecipient r SET r.firstClickedAt = :timestamp WHERE r.id = :id AND r.firstClickedAt IS NULL")
    int updateFirstClickedAt(@org.springframework.data.repository.query.Param("id") UUID id, @org.springframework.data.repository.query.Param("timestamp") java.time.LocalDateTime timestamp);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("UPDATE EmailCampaignRecipient r SET r.deliveryStatus = :sendingStatus WHERE r.id = :id AND r.deliveryStatus = :pendingStatus")
    int claimRecipientForSendingInternal(@org.springframework.data.repository.query.Param("id") UUID id,
                                         @org.springframework.data.repository.query.Param("sendingStatus") EmailCampaignRecipient.DeliveryStatus sendingStatus,
                                         @org.springframework.data.repository.query.Param("pendingStatus") EmailCampaignRecipient.DeliveryStatus pendingStatus);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("UPDATE EmailCampaignRecipient r SET r.deliveryStatus = :sendingStatus WHERE r.id = :id AND r.tenant.id = :tenantId AND r.deliveryStatus = :pendingStatus")
    int claimRecipientForSendingInternal(@org.springframework.data.repository.query.Param("id") UUID id,
                                         @org.springframework.data.repository.query.Param("tenantId") UUID tenantId,
                                         @org.springframework.data.repository.query.Param("sendingStatus") EmailCampaignRecipient.DeliveryStatus sendingStatus,
                                         @org.springframework.data.repository.query.Param("pendingStatus") EmailCampaignRecipient.DeliveryStatus pendingStatus);

    default int claimRecipientForSending(UUID id) {
        return claimRecipientForSendingInternal(id, EmailCampaignRecipient.DeliveryStatus.SENDING, EmailCampaignRecipient.DeliveryStatus.PENDING);
    }

    default int claimRecipientForSending(UUID id, UUID tenantId) {
        return claimRecipientForSendingInternal(id, tenantId, EmailCampaignRecipient.DeliveryStatus.SENDING, EmailCampaignRecipient.DeliveryStatus.PENDING);
    }
}

