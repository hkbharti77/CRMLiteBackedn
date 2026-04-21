package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.Booking;
import com.chatcrmlite.backend.models.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BookingRepository extends JpaRepository<Booking, UUID> {

    List<Booking> findByOwner_IdOrderByCreatedAtDesc(UUID ownerId);

    List<Booking> findByLead_IdAndOwner_IdOrderByCreatedAtDesc(UUID leadId, UUID ownerId);

    List<Booking> findByOwner_IdAndStatus(UUID ownerId, Booking.BookingStatus status);
}
