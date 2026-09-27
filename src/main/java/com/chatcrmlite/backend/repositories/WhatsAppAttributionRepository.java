package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.WhatsAppAttribution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface WhatsAppAttributionRepository extends JpaRepository<WhatsAppAttribution, UUID> {
    Optional<WhatsAppAttribution> findByTenantIdAndConversationId(UUID tenantId, UUID conversationId);
    Optional<WhatsAppAttribution> findTopByTenantIdAndPhoneNumberIdOrderByCapturedAtDesc(UUID tenantId, String phoneNumberId);
}
