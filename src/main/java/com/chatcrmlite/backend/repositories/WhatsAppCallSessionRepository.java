package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.whatsapp.WhatsAppCallSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface WhatsAppCallSessionRepository extends JpaRepository<WhatsAppCallSession, UUID> {

    @Query("SELECT s FROM WhatsAppCallSession s WHERE s.tenant.id = :tenantId AND s.callId = :callId")
    Optional<WhatsAppCallSession> findByTenantIdAndCallId(
        @Param("tenantId") UUID tenantId,
        @Param("callId") String callId
    );

    @Query("SELECT s FROM WhatsAppCallSession s WHERE s.callId = :callId")
    Optional<WhatsAppCallSession> findByCallId(@Param("callId") String callId);

    @Query("SELECT COUNT(s) FROM WhatsAppCallSession s WHERE s.tenant.id = :tenantId AND s.phoneNumberId = :phoneNumberId AND s.state IN ('MEDIA_NEGOTIATING', 'MEDIA_READY', 'ACCEPTING', 'ACCEPTED_200_OK', 'MEDIA_FLOWING', 'ACTIVE')")
    long countActiveCallsForPhone(
        @Param("tenantId") UUID tenantId,
        @Param("phoneNumberId") String phoneNumberId
    );
}
