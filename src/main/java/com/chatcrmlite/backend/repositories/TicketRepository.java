package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.Ticket;
import com.chatcrmlite.backend.models.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TicketRepository extends JpaRepository<Ticket, UUID> {

    /** All non-deleted tickets for a business owner (paginated) */
    @Query("SELECT t FROM Ticket t LEFT JOIN FETCH t.contact LEFT JOIN FETCH t.assignedTo " +
           "WHERE t.owner = :owner AND t.deleted = false ORDER BY t.createdAt DESC")
    Page<Ticket> findAllByOwnerActivePaged(User owner, Pageable pageable);

    /** Tickets by status (paginated) */
    @Query("SELECT t FROM Ticket t LEFT JOIN FETCH t.contact LEFT JOIN FETCH t.assignedTo " +
           "WHERE t.owner = :owner AND t.status = :status AND t.deleted = false ORDER BY t.createdAt DESC")
    Page<Ticket> findAllByOwnerAndStatusPaged(User owner, Ticket.TicketStatus status, Pageable pageable);

    /**
     * Full-text search using PostgreSQL GIN index on search_vector (AP-8 fix).
     * Eliminates the sequential scan caused by 4x LIKE '%term%' clauses.
     * Uses the search_vector generated column added in V10031.
     *
     * Example query: plainto_tsquery('english', 'billing issue')
     */
    @Query(value = """
            SELECT t.* FROM tickets t
            WHERE t.owner_id = :ownerId
              AND t.deleted = false
              AND t.search_vector @@ plainto_tsquery('english', :query)
            ORDER BY ts_rank(t.search_vector, plainto_tsquery('english', :query)) DESC,
                     t.created_at DESC
            """, nativeQuery = true)
    Page<Ticket> searchTicketsFts(@Param("ownerId") UUID ownerId,
                                  @Param("query") String query,
                                  Pageable pageable);

    /**
     * @deprecated AP-8: Use searchTicketsFts() instead.
     * This query causes a full sequential scan on large ticket tables because
     * LIKE '%term%' with a leading wildcard cannot use any index.
     */
    @Deprecated
    @Query("SELECT t FROM Ticket t LEFT JOIN FETCH t.contact LEFT JOIN FETCH t.assignedTo " +
           "WHERE t.owner = :owner AND t.deleted = false " +
           "AND (LOWER(t.subject) LIKE LOWER(CONCAT('%', :query, '%')) " +
           "OR LOWER(t.description) LIKE LOWER(CONCAT('%', :query, '%')) " +
           "OR LOWER(t.submitterName) LIKE LOWER(CONCAT('%', :query, '%')) " +
           "OR LOWER(t.submitterEmail) LIKE LOWER(CONCAT('%', :query, '%'))) " +
           "ORDER BY t.createdAt DESC")
    Page<Ticket> searchTickets(@Param("owner") User owner, @Param("query") String query, Pageable pageable);

    /** Tickets assigned to a specific agent */
    @Query("SELECT t FROM Ticket t LEFT JOIN FETCH t.contact " +
           "WHERE t.assignedTo = :agent AND t.deleted = false ORDER BY t.createdAt DESC")
    List<Ticket> findAllByAssignedTo(User agent);

    /** Single ticket with contact eagerly loaded */
    @Query("SELECT t FROM Ticket t LEFT JOIN FETCH t.contact LEFT JOIN FETCH t.assignedTo " +
           "WHERE t.id = :id AND t.deleted = false")
    Optional<Ticket> findByIdActive(UUID id);

    /** Find by ticket number */
    @Query("SELECT t FROM Ticket t LEFT JOIN FETCH t.contact LEFT JOIN FETCH t.assignedTo " +
           "WHERE t.ticketNumber = :ticketNumber AND t.deleted = false")
    Optional<Ticket> findByTicketNumber(String ticketNumber);

    /** Count open tickets for a business */
    long countByOwnerAndStatusAndDeletedFalse(User owner, Ticket.TicketStatus status);

    /** Tickets by source */
    List<Ticket> findAllByOwnerAndSourceAndDeletedFalse(User owner, Ticket.TicketSource source);

    /** Duplicate detection: same email + similar subject within time window */
    @Query("SELECT t FROM Ticket t WHERE t.owner = :owner " +
           "AND t.submitterEmail = :email " +
           "AND LOWER(t.subject) = LOWER(:subject) " +
           "AND t.createdAt > :since " +
           "AND t.deleted = false")
    List<Ticket> findPotentialDuplicates(@Param("owner") User owner,
                                         @Param("email") String email,
                                         @Param("subject") String subject,
                                         @Param("since") LocalDateTime since);

    /** SLA breach detection: tickets with overdue response/resolution */
    @Query("SELECT t FROM Ticket t WHERE t.owner = :owner " +
           "AND t.deleted = false " +
           "AND t.slaBreached = false " +
           "AND (t.firstResponseDueAt < :now OR t.resolutionDueAt < :now)")
    List<Ticket> findSlaBreachCandidates(@Param("owner") User owner, @Param("now") LocalDateTime now);

    /**
     * Get next ticket number using the database sequence (AP-8 fix).
     * Replaces COUNT(*) which is O(n) and not safe under concurrent inserts.
     */
    @Query(value = "SELECT NEXTVAL('ticket_number_seq')", nativeQuery = true)
    long nextTicketNumber();

    /**
     * @deprecated Use nextTicketNumber() which uses a DB sequence (O(1) + concurrent-safe).
     * COUNT(*) is O(n) and races under concurrent ticket creation.
     */
    @Deprecated
    @Query("SELECT COUNT(t) FROM Ticket t WHERE t.owner = :owner")
    long countByOwner(User owner);
    // Count tickets created today with a specific date prefix (for reference number generation)
    @Query(value = "SELECT COUNT(t) FROM tickets t WHERE t.owner_id = :ownerId AND t.reference_number LIKE :datePrefix || '%'", nativeQuery = true)
    long countByOwnerAndDatePrefix(@Param("ownerId") UUID ownerId, @Param("datePrefix") String datePrefix);
}
