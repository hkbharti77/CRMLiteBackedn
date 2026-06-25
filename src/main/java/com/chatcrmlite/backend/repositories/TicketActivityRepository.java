package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.Ticket;
import com.chatcrmlite.backend.models.TicketActivity;
import com.chatcrmlite.backend.models.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TicketActivityRepository extends JpaRepository<TicketActivity, UUID> {

    /** All activities for a ticket, ordered by creation time */
    List<TicketActivity> findAllByTicketOrderByCreatedAtDesc(Ticket ticket);

    /** Recent activities across all tickets for a business */
    List<TicketActivity> findTop50ByTicket_OwnerIdOrderByCreatedAtDesc(UUID ownerId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("UPDATE TicketActivity a SET a.user = null WHERE a.user = :user")
    void nullifyUser(@org.springframework.data.repository.query.Param("user") User user);
}
