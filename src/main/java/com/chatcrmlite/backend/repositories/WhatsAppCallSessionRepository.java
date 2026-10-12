package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.whatsapp.WhatsAppCallSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WhatsAppCallSessionRepository extends JpaRepository<WhatsAppCallSession, UUID>, JpaSpecificationExecutor<WhatsAppCallSession> {

    @Query("SELECT s FROM WhatsAppCallSession s WHERE s.tenant.id = :tenantId AND s.callId = :callId")
    Optional<WhatsAppCallSession> findByTenantIdAndCallId(
        @Param("tenantId") UUID tenantId,
        @Param("callId") String callId
    );

    @Query("SELECT s FROM WhatsAppCallSession s WHERE s.callId = :callId")
    Optional<WhatsAppCallSession> findByCallId(@Param("callId") String callId);

    @Query("SELECT COUNT(s) FROM WhatsAppCallSession s WHERE s.tenant.id = :tenantId AND s.phoneNumberId = :phoneNumberId AND s.state IN ('PRE_ACCEPTING', 'MEDIA_NEGOTIATING', 'MEDIA_READY', 'ACCEPTING', 'ACCEPTED_200_OK', 'MEDIA_FLOWING', 'ACTIVE', 'OFFER_SENT', 'SDP_ANSWER_RECEIVED', 'MEDIA_CONNECTED')")
    long countActiveCallsForPhone(
        @Param("tenantId") UUID tenantId,
        @Param("phoneNumberId") String phoneNumberId
    );

    @Query("SELECT COUNT(s) FROM WhatsAppCallSession s WHERE s.tenant.id = :tenantId " +
           "AND (cast(:phoneNumberId as string) IS NULL OR s.phoneNumberId = :phoneNumberId) " +
           "AND (cast(:from as timestamp) IS NULL OR s.createdAt >= :from) " +
           "AND (cast(:to as timestamp) IS NULL OR s.createdAt <= :to)")
    long countCallsByTenant(
        @Param("tenantId") UUID tenantId,
        @Param("phoneNumberId") String phoneNumberId,
        @Param("from") Instant from,
        @Param("to") Instant to
    );

    @Query("SELECT s.direction, COUNT(s) FROM WhatsAppCallSession s WHERE s.tenant.id = :tenantId " +
           "AND (cast(:phoneNumberId as string) IS NULL OR s.phoneNumberId = :phoneNumberId) " +
           "AND (cast(:from as timestamp) IS NULL OR s.createdAt >= :from) " +
           "AND (cast(:to as timestamp) IS NULL OR s.createdAt <= :to) " +
           "GROUP BY s.direction")
    List<Object[]> countCallsByDirection(
        @Param("tenantId") UUID tenantId,
        @Param("phoneNumberId") String phoneNumberId,
        @Param("from") Instant from,
        @Param("to") Instant to
    );

    @Query("SELECT s.state, COUNT(s) FROM WhatsAppCallSession s WHERE s.tenant.id = :tenantId " +
           "AND (cast(:phoneNumberId as string) IS NULL OR s.phoneNumberId = :phoneNumberId) " +
           "AND (cast(:from as timestamp) IS NULL OR s.createdAt >= :from) " +
           "AND (cast(:to as timestamp) IS NULL OR s.createdAt <= :to) " +
           "GROUP BY s.state")
    List<Object[]> countCallsByState(
        @Param("tenantId") UUID tenantId,
        @Param("phoneNumberId") String phoneNumberId,
        @Param("from") Instant from,
        @Param("to") Instant to
    );

    @Query("SELECT s.terminationReason, COUNT(s) FROM WhatsAppCallSession s WHERE s.tenant.id = :tenantId " +
           "AND (cast(:phoneNumberId as string) IS NULL OR s.phoneNumberId = :phoneNumberId) " +
           "AND (cast(:from as timestamp) IS NULL OR s.createdAt >= :from) " +
           "AND (cast(:to as timestamp) IS NULL OR s.createdAt <= :to) " +
           "AND s.terminationReason IS NOT NULL " +
           "GROUP BY s.terminationReason")
    List<Object[]> countCallsByTerminationReason(
        @Param("tenantId") UUID tenantId,
        @Param("phoneNumberId") String phoneNumberId,
        @Param("from") Instant from,
        @Param("to") Instant to
    );

    @Query("SELECT COUNT(s) FROM WhatsAppCallSession s WHERE s.tenant.id = :tenantId " +
           "AND (cast(:phoneNumberId as string) IS NULL OR s.phoneNumberId = :phoneNumberId) " +
           "AND (cast(:from as timestamp) IS NULL OR s.createdAt >= :from) " +
           "AND (cast(:to as timestamp) IS NULL OR s.createdAt <= :to) " +
           "AND s.connectedAt IS NOT NULL")
    long countAnsweredCalls(
        @Param("tenantId") UUID tenantId,
        @Param("phoneNumberId") String phoneNumberId,
        @Param("from") Instant from,
        @Param("to") Instant to
    );

    @Query("SELECT COALESCE(SUM(s.durationSeconds), 0), COALESCE(AVG(s.durationSeconds), 0.0) FROM WhatsAppCallSession s WHERE s.tenant.id = :tenantId " +
           "AND (cast(:phoneNumberId as string) IS NULL OR s.phoneNumberId = :phoneNumberId) " +
           "AND (cast(:from as timestamp) IS NULL OR s.createdAt >= :from) " +
           "AND (cast(:to as timestamp) IS NULL OR s.createdAt <= :to) " +
           "AND s.durationSeconds IS NOT NULL AND s.durationSeconds > 0")
    List<Object[]> getDurationStats(
        @Param("tenantId") UUID tenantId,
        @Param("phoneNumberId") String phoneNumberId,
        @Param("from") Instant from,
        @Param("to") Instant to
    );
}

