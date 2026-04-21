package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.Appointment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface AppointmentRepository extends JpaRepository<Appointment, UUID> {

    // All appointments for a user — soonest first
    List<Appointment> findByOwner_IdOrderByAppointmentDateTimeAsc(UUID ownerId);

    // Appointments for a specific lead
    List<Appointment> findByLead_IdAndOwner_IdOrderByAppointmentDateTimeAsc(
            UUID leadId, UUID ownerId);

    // Today's appointments (between start and end of day, filtered by status)
    List<Appointment> findByOwner_IdAndAppointmentDateTimeBetweenAndStatus(
            UUID ownerId,
            LocalDateTime start,
            LocalDateTime end,
            Appointment.AppointmentStatus status);

    // Upcoming SCHEDULED appointments (after now)
    List<Appointment> findByOwner_IdAndAppointmentDateTimeAfterAndStatusOrderByAppointmentDateTimeAsc(
            UUID ownerId,
            LocalDateTime after,
            Appointment.AppointmentStatus status);

    // Count today's scheduled (for dashboard widget)
    long countByOwner_IdAndAppointmentDateTimeBetweenAndStatus(
            UUID ownerId,
            LocalDateTime start,
            LocalDateTime end,
            Appointment.AppointmentStatus status);
}
