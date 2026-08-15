package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.Appointment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AppointmentRepository extends JpaRepository<Appointment, UUID> {

    // All appointments for a user — soonest first, with contact eagerly loaded
    @Query("SELECT a FROM Appointment a JOIN FETCH a.contact c WHERE a.owner.id = :ownerId ORDER BY a.appointmentDateTime ASC")
    List<Appointment> findByOwner_IdOrderByAppointmentDateTimeAsc(@Param("ownerId") UUID ownerId);

    // Appointments for a specific contact, with contact eagerly loaded
    @Query("SELECT a FROM Appointment a JOIN FETCH a.contact c WHERE c.id = :contactId AND a.owner.id = :ownerId ORDER BY a.appointmentDateTime ASC")
    List<Appointment> findByContact_IdAndOwner_IdOrderByAppointmentDateTimeAsc(
            @Param("contactId") UUID contactId, @Param("ownerId") UUID ownerId);

    // Today's appointments (between start and end of day, filtered by status), with contact eagerly loaded
    @Query("SELECT a FROM Appointment a JOIN FETCH a.contact c WHERE a.owner.id = :ownerId AND a.appointmentDateTime BETWEEN :start AND :end AND a.status = :status")
    List<Appointment> findByOwner_IdAndAppointmentDateTimeBetweenAndStatus(
            @Param("ownerId") UUID ownerId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end,
            @Param("status") Appointment.AppointmentStatus status);

    // Upcoming SCHEDULED appointments (after now), with contact eagerly loaded
    @Query("SELECT a FROM Appointment a JOIN FETCH a.contact c WHERE a.owner.id = :ownerId AND a.appointmentDateTime > :after AND a.status = :status ORDER BY a.appointmentDateTime ASC")
    List<Appointment> findByOwner_IdAndAppointmentDateTimeAfterAndStatusOrderByAppointmentDateTimeAsc(
            @Param("ownerId") UUID ownerId,
            @Param("after") LocalDateTime after,
            @Param("status") Appointment.AppointmentStatus status);

    // Count today's scheduled (for dashboard widget) — count query doesn't need JOIN FETCH
    long countByOwner_IdAndAppointmentDateTimeBetweenAndStatus(
            UUID ownerId,
            LocalDateTime start,
            LocalDateTime end,
            Appointment.AppointmentStatus status);

    // Single appointment lookup with contact eagerly loaded
    @Query("SELECT a FROM Appointment a JOIN FETCH a.contact c WHERE a.id = :id")
    Optional<Appointment> findByIdWithContact(@Param("id") UUID id);

    // Count appointments created today with a specific date prefix (for reference number generation)
    @Query(value = "SELECT COUNT(a) FROM appointments a WHERE a.owner_id = :ownerId AND a.reference_number LIKE :datePrefix || '%'", nativeQuery = true)
    long countByOwnerAndDatePrefix(@Param("ownerId") UUID ownerId, @Param("datePrefix") String datePrefix);

    // ── Tenant-Wide Methods (Strictly isolated by tenantId) ──

    @Query("SELECT a FROM Appointment a JOIN FETCH a.contact c WHERE a.owner.tenant.id = :tenantId ORDER BY a.appointmentDateTime ASC")
    List<Appointment> findAllByTenantIdOrderByAppointmentDateTimeAsc(@Param("tenantId") UUID tenantId);

    @Query("SELECT a FROM Appointment a JOIN FETCH a.contact c WHERE c.id = :contactId AND a.owner.tenant.id = :tenantId ORDER BY a.appointmentDateTime ASC")
    List<Appointment> findByContactIdAndTenantIdOrderByAppointmentDateTimeAsc(@Param("contactId") UUID contactId, @Param("tenantId") UUID tenantId);

    @Query("SELECT a FROM Appointment a JOIN FETCH a.contact c WHERE a.owner.tenant.id = :tenantId AND a.appointmentDateTime BETWEEN :start AND :end AND a.status = :status")
    List<Appointment> findByTenantIdAndAppointmentDateTimeBetweenAndStatus(
            @Param("tenantId") UUID tenantId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end,
            @Param("status") Appointment.AppointmentStatus status);

    @Query("SELECT a FROM Appointment a JOIN FETCH a.contact c ORDER BY a.appointmentDateTime ASC")
    List<Appointment> findAllOrderByAppointmentDateTimeAsc();

    @Query("SELECT a FROM Appointment a JOIN FETCH a.contact c WHERE c.id = :contactId ORDER BY a.appointmentDateTime ASC")
    List<Appointment> findByContact_IdOrderByAppointmentDateTimeAsc(@Param("contactId") UUID contactId);

    @Query("SELECT a FROM Appointment a JOIN FETCH a.contact c WHERE a.appointmentDateTime BETWEEN :start AND :end AND a.status = :status")
    List<Appointment> findByAppointmentDateTimeBetweenAndStatus(
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end,
            @Param("status") Appointment.AppointmentStatus status);

    @Query("SELECT a FROM Appointment a JOIN FETCH a.contact c WHERE a.appointmentDateTime > :after AND a.status = :status ORDER BY a.appointmentDateTime ASC")
    List<Appointment> findByAppointmentDateTimeAfterAndStatusOrderByAppointmentDateTimeAsc(
            @Param("after") LocalDateTime after,
            @Param("status") Appointment.AppointmentStatus status);

    long countByAppointmentDateTimeBetweenAndStatus(
            LocalDateTime start,
            LocalDateTime end,
            Appointment.AppointmentStatus status);

    @Query("SELECT COUNT(a) FROM Appointment a WHERE a.tenant.id = :tenantId")
    long countByTenantId(@Param("tenantId") UUID tenantId);
}
