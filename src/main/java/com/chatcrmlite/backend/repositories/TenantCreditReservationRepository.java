package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.TenantCreditReservation;
import com.chatcrmlite.backend.models.TenantCreditReservation.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TenantCreditReservationRepository extends JpaRepository<TenantCreditReservation, UUID> {

    Optional<TenantCreditReservation> findByIdAndStatus(UUID id, ReservationStatus status);

    List<TenantCreditReservation> findByTenantIdAndStatus(UUID tenantId, ReservationStatus status);

    @Query("SELECT COALESCE(SUM(r.amount), 0) FROM TenantCreditReservation r WHERE r.tenant.id = :tenantId AND r.status = 'PENDING' AND r.expiresAt > :now")
    long sumActiveReservedCredits(@Param("tenantId") UUID tenantId, @Param("now") LocalDateTime now);

    @Query("SELECT r FROM TenantCreditReservation r WHERE r.status = 'PENDING' AND r.expiresAt <= :now")
    List<TenantCreditReservation> findStaleReservations(@Param("now") LocalDateTime now);
}
