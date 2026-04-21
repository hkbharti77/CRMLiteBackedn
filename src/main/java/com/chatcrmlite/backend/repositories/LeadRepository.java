package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LeadRepository extends JpaRepository<Lead, UUID> {
    List<Lead> findAllByOwner(User owner);
    List<Lead> findAllByStatusAndOwner(Lead.LeadStatus status, User owner);
    List<Lead> findAllByOwnerAndDeletedTrue(User owner);

    /** All leads for a contact (multiple leads per contact supported) */
    List<Lead> findAllByContact(Contact contact);

    /** Latest lead for a contact — used for quick status checks */
    Optional<Lead> findTopByContactOrderByCreatedAtDesc(Contact contact);

    /** Active (non-closed) lead for a contact — used by flow engine */
    Optional<Lead> findTopByContactAndStatusNotInOrderByCreatedAtDesc(
            Contact contact, List<Lead.LeadStatus> excludedStatuses);

    /** Paginated leads for a user — used by performance-optimized endpoints */
    @Query("SELECT l FROM Lead l WHERE l.owner = :owner AND l.deleted = false")
    Page<Lead> findAllByOwnerPaged(User owner, Pageable pageable);

    /** Optimized: fetch leads with contact eagerly to avoid N+1 queries */
    @Query("SELECT l FROM Lead l JOIN FETCH l.contact WHERE l.owner = :owner AND l.deleted = false ORDER BY l.lastActivity DESC")
    List<Lead> findAllByOwnerWithContact(User owner);

    /** Optimized: fetch all leads for a contact with owner in one query */
    @Query("SELECT l FROM Lead l JOIN FETCH l.contact c WHERE c = :contact AND l.owner = :owner ORDER BY l.createdAt DESC")
    List<Lead> findAllByContactAndOwnerOptimized(Contact contact, User owner);
}
