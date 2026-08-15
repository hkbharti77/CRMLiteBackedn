package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.Booking;
import com.chatcrmlite.backend.models.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BookingRepository extends JpaRepository<Booking, UUID> {

    @Query("SELECT b FROM Booking b JOIN FETCH b.contact c WHERE b.owner.id = :ownerId ORDER BY b.createdAt DESC")
    List<Booking> findByOwner_IdOrderByCreatedAtDesc(@Param("ownerId") UUID ownerId);

    @Query("SELECT b FROM Booking b JOIN FETCH b.contact c WHERE c.id = :contactId AND b.owner.id = :ownerId ORDER BY b.createdAt DESC")
    List<Booking> findByContact_IdAndOwner_IdOrderByCreatedAtDesc(@Param("contactId") UUID contactId, @Param("ownerId") UUID ownerId);

    @Query("SELECT b FROM Booking b JOIN FETCH b.contact c WHERE b.owner.id = :ownerId AND b.status = :status")
    List<Booking> findByOwner_IdAndStatus(@Param("ownerId") UUID ownerId, @Param("status") Booking.BookingStatus status);

    @Query("SELECT b FROM Booking b JOIN FETCH b.contact c WHERE b.id = :id")
    Optional<Booking> findByIdWithContact(@Param("id") UUID id);

    // Count bookings created today with a specific date prefix (for reference number generation)
    @Query(value = "SELECT COUNT(b) FROM bookings b WHERE b.owner_id = :ownerId AND b.reference_number LIKE :datePrefix || '%'", nativeQuery = true)
    long countByOwnerAndDatePrefix(@Param("ownerId") UUID ownerId, @Param("datePrefix") String datePrefix);

    // ── Tenant-Wide Methods (Strictly isolated by tenantId) ──

    @Query("SELECT b FROM Booking b JOIN FETCH b.contact c WHERE b.owner.tenant.id = :tenantId ORDER BY b.createdAt DESC")
    List<Booking> findAllByTenantIdOrderByCreatedAtDesc(@Param("tenantId") UUID tenantId);

    @Query("SELECT b FROM Booking b JOIN FETCH b.contact c WHERE c.id = :contactId AND b.owner.tenant.id = :tenantId ORDER BY b.createdAt DESC")
    List<Booking> findByContactIdAndTenantIdOrderByCreatedAtDesc(@Param("contactId") UUID contactId, @Param("tenantId") UUID tenantId);

    @Query("SELECT b FROM Booking b JOIN FETCH b.contact c WHERE b.owner.tenant.id = :tenantId AND b.status = :status")
    List<Booking> findByTenantIdAndStatus(@Param("tenantId") UUID tenantId, @Param("status") Booking.BookingStatus status);

    @Query("SELECT b FROM Booking b JOIN FETCH b.contact c ORDER BY b.createdAt DESC")
    List<Booking> findAllOrderByCreatedAtDesc();

    @Query("SELECT b FROM Booking b JOIN FETCH b.contact c WHERE c.id = :contactId ORDER BY b.createdAt DESC")
    List<Booking> findByContact_IdOrderByCreatedAtDesc(@Param("contactId") UUID contactId);

    @Query("SELECT b FROM Booking b JOIN FETCH b.contact c WHERE b.status = :status")
    List<Booking> findByStatus(@Param("status") Booking.BookingStatus status);

    @Query("SELECT COUNT(b) FROM Booking b WHERE b.owner.tenant.id = :tenantId")
    long countByTenantId(@Param("tenantId") UUID tenantId);
}
