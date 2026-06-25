package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.Ticket;
import com.chatcrmlite.backend.models.TicketComment;
import com.chatcrmlite.backend.models.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface TicketCommentRepository extends JpaRepository<TicketComment, UUID> {

    /** All non-deleted comments for a ticket, ordered by creation time */
    @Query("SELECT c FROM TicketComment c WHERE c.ticket = :ticket AND c.deleted = false ORDER BY c.createdAt ASC")
    List<TicketComment> findAllByTicketActive(Ticket ticket);

    /** Count comments for a ticket */
    long countByTicketAndDeletedFalse(Ticket ticket);

    /** Internal comments only */
    @Query("SELECT c FROM TicketComment c WHERE c.ticket = :ticket AND c.internal = true AND c.deleted = false ORDER BY c.createdAt ASC")
    List<TicketComment> findInternalComments(Ticket ticket);

    /** Customer-visible comments only */
    @Query("SELECT c FROM TicketComment c WHERE c.ticket = :ticket AND c.internal = false AND c.deleted = false ORDER BY c.createdAt ASC")
    List<TicketComment> findCustomerVisibleComments(Ticket ticket);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("UPDATE TicketComment c SET c.author = null WHERE c.author = :user")
    void nullifyAuthor(@org.springframework.data.repository.query.Param("user") User user);
}
