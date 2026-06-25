package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.ActivityLog;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;


@Repository
public interface ActivityLogRepository extends JpaRepository<ActivityLog, UUID> {

    /**
     * Paginated CRM timeline for a single contact (preferred — AP-7 fix).
     * Prevents loading 100k+ rows into memory for high-volume contacts.
     */
    Page<ActivityLog> findByContactOrderByCreatedAtDesc(Contact contact, Pageable pageable);

    /**
     * @deprecated Use paginated version: findByContactOrderByCreatedAtDesc(contact, pageable).
     * This unbounded query risks OOM for contacts with large histories.
     */
    @Deprecated
    List<ActivityLog> findByContactOrderByCreatedAtDesc(Contact contact);

    /**
     * Paginated activity feed for an owner (tenant) dashboard (preferred — AP-7 fix).
     */
    Page<ActivityLog> findByOwnerOrderByCreatedAtDesc(User owner, Pageable pageable);

    /**
     * @deprecated Use paginated version: findByOwnerOrderByCreatedAtDesc(owner, pageable).
     * Returns ALL logs for a tenant — unbounded, risks OOM.
     */
    @Deprecated
    List<ActivityLog> findByOwnerOrderByCreatedAtDesc(User owner);

    /**
     * Fetch all logs for a specific entity (e.g., a single booking's history).
     */
    @Query("SELECT a FROM ActivityLog a WHERE a.entityType = :type AND a.entityId = :id ORDER BY a.createdAt DESC")
    List<ActivityLog> findByEntity(@Param("type") String entityType, @Param("id") UUID entityId);

    /**
     * Fetch recent N activities for a contact — used for a quick-glance sidebar.
     */
    @Query(value = """
            SELECT * FROM activity_logs
            WHERE contact_id = :contactId
            ORDER BY created_at DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<ActivityLog> findRecentByContactId(@Param("contactId") UUID contactId,
                                            @Param("limit") int limit);

    /**
     * Count all activities for an owner — for analytics dashboards.
     */
    long countByOwner(User owner);

    @Query("SELECT a FROM ActivityLog a WHERE a.owner.tenant.id = :tenantId ORDER BY a.createdAt DESC")
    List<ActivityLog> findByTenantIdOrderByCreatedAtDesc(@Param("tenantId") UUID tenantId);
}

