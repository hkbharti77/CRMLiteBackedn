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
import org.springframework.data.repository.query.Param;

public interface LeadRepository extends JpaRepository<Lead, UUID> {
    @Query("SELECT DISTINCT l FROM Lead l " +
           "LEFT JOIN FETCH l.owner o " +
           "LEFT JOIN FETCH o.tenant " +
           "WHERE l.id = :id")
    Optional<Lead> findByIdWithOwnerAndTenant(@Param("id") UUID id);
    
    Optional<Lead> findByLeadNumber(String leadNumber);

    List<Lead> findByOwnerAndStatusIn(User owner, List<Lead.LeadStatus> statuses);

    List<Lead> findAllByOwner(User owner);
    /** Optimized: fetch leads by status with contact and tags eagerly to avoid lazy initialization */
    @Query("SELECT DISTINCT l FROM Lead l " +
           "JOIN FETCH l.contact c " +
           "LEFT JOIN FETCH c.tags " +
           "WHERE l.status = :status AND l.owner = :owner " +
           "ORDER BY l.lastActivity DESC")
    List<Lead> findAllByStatusAndOwner(@Param("status") Lead.LeadStatus status, @Param("owner") User owner);

    @Query("SELECT DISTINCT l FROM Lead l " +
           "JOIN FETCH l.contact c " +
           "LEFT JOIN FETCH c.tags " +
           "WHERE l.status = :status AND l.owner = :owner " +
           "ORDER BY l.lastActivity DESC")
    Page<Lead> findAllByStatusAndOwnerPaged(@Param("status") Lead.LeadStatus status, @Param("owner") User owner, Pageable pageable);

    List<Lead> findAllByOwnerAndDeletedTrue(User owner);

    /** All leads for a contact (multiple leads per contact supported) — eager-load tags */
    @Query("SELECT DISTINCT l FROM Lead l " +
           "JOIN FETCH l.contact c " +
           "LEFT JOIN FETCH c.tags " +
           "WHERE c = :contact " +
           "ORDER BY l.createdAt DESC")
    List<Lead> findAllByContact(@Param("contact") Contact contact);

    /** Latest lead for a contact — used for quick status checks */
    Optional<Lead> findTopByContactOrderByCreatedAtDesc(Contact contact);

    /** Active (non-closed) lead for a contact — used by flow engine */
    Optional<Lead> findTopByContactAndStatusNotInOrderByCreatedAtDesc(
            Contact contact, List<Lead.LeadStatus> excludedStatuses);

    /** Optimized: fetch paginated leads with contact and tags eagerly to avoid lazy initialization */
    @Query("SELECT DISTINCT l FROM Lead l " +
           "JOIN FETCH l.contact c " +
           "LEFT JOIN FETCH c.tags " +
           "WHERE l.owner = :owner " +
           "ORDER BY l.lastActivity DESC")
    Page<Lead> findAllByOwnerPaged(@Param("owner") User owner, Pageable pageable);

    /** Optimized: fetch leads with contact eagerly to avoid N+1 queries */
    @Query("SELECT l FROM Lead l JOIN FETCH l.contact WHERE l.owner = :owner ORDER BY l.lastActivity DESC")
    List<Lead> findAllByOwnerWithContact(User owner);

    /** Optimized: fetch leads with contact and tags eagerly to avoid lazy initialization */
    @Query("SELECT DISTINCT l FROM Lead l " +
           "JOIN FETCH l.contact c " +
           "LEFT JOIN FETCH c.tags " +
           "WHERE l.owner = :owner " +
           "ORDER BY l.lastActivity DESC")
    List<Lead> findAllByOwnerWithContactAndTags(@Param("owner") User owner);

    /** Optimized: fetch all leads for a contact with owner and tags in one query */
    @Query("SELECT DISTINCT l FROM Lead l " +
           "JOIN FETCH l.contact c " +
           "LEFT JOIN FETCH c.tags " +
           "WHERE c = :contact AND l.owner = :owner " +
           "ORDER BY l.createdAt DESC")
    List<Lead> findAllByContactAndOwnerOptimized(@Param("contact") Contact contact, @Param("owner") User owner);

    @Query("SELECT COUNT(l) FROM Lead l WHERE l.contact = :contact AND l.owner = :owner AND l.status NOT IN :excludedStatuses")
    long countByContactAndOwnerAndStatusNotIn(
            @Param("contact") Contact contact, 
            @Param("owner") User owner, 
            @Param("excludedStatuses") List<Lead.LeadStatus> excludedStatuses);

    @Query("SELECT new com.chatcrmlite.backend.dto.RevenueReportDTO(" +
           "COALESCE(SUM(l.dealValue), 0), " +
           "COALESCE(SUM(CASE WHEN l.paymentStatus = 'PAID' THEN l.dealValue ELSE 0 END), 0), " +
           "COALESCE(SUM(CASE WHEN l.paymentStatus IN ('PENDING', 'PARTIAL') THEN l.dealValue ELSE 0 END), 0), " +
           "COUNT(CASE WHEN l.dealValue IS NOT NULL THEN 1 END), " +
           "COUNT(CASE WHEN l.paymentStatus = 'PAID' THEN 1 END), " +
           "COUNT(CASE WHEN l.paymentStatus IN ('PENDING', 'PARTIAL') THEN 1 END), " +
           "'INR') " +
           "FROM Lead l WHERE l.owner = :owner")
    com.chatcrmlite.backend.dto.RevenueReportDTO calculateRevenueReport(@Param("owner") User owner);

    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE Lead l SET l.lastActivity = :lastActivity WHERE l.id = :id")
    void updateLastActivity(@Param("id") UUID id, @Param("lastActivity") java.time.LocalDateTime lastActivity);

    /** Count total leads for an owner — used to generate sequential lead numbers */
    @Query("SELECT COUNT(l) FROM Lead l WHERE l.owner = :owner")
    long countByOwner(@Param("owner") User owner);

    @Query("SELECT COUNT(l) FROM Lead l WHERE l.status = :status AND l.owner = :owner")
    long countByStatusAndOwner(@Param("status") Lead.LeadStatus status, @Param("owner") User owner);

    /** Count leads created today for a specific owner (for reference number generation) */
    @Query("SELECT COUNT(l) FROM Lead l WHERE l.owner = :owner AND CAST(l.createdAt AS date) = CAST(CURRENT_TIMESTAMP AS date)")
    long countByOwnerAndToday(@Param("owner") User owner);

    /** Count leads created today with a specific date prefix (for reference number generation) */
    @Query(value = "SELECT COUNT(l) FROM leads l WHERE l.owner_id = :ownerId AND l.lead_number LIKE :datePrefix || '%'", nativeQuery = true)
    long countByOwnerAndDatePrefix(@Param("ownerId") UUID ownerId, @Param("datePrefix") String datePrefix);

    // ── Tenant-Wide Methods (Filtered automatically by Hibernate @Filter) ──

    /** Optimized: fetch paginated leads with contact and tags eagerly to avoid lazy initialization */
    @Query("SELECT DISTINCT l FROM Lead l " +
           "JOIN FETCH l.contact c " +
           "LEFT JOIN FETCH c.tags " +
           "ORDER BY l.lastActivity DESC")
    Page<Lead> findAllPaged(Pageable pageable);

    @Query("SELECT DISTINCT l FROM Lead l " +
           "JOIN FETCH l.contact c " +
           "LEFT JOIN FETCH c.tags " +
           "WHERE l.status = :status " +
           "ORDER BY l.lastActivity DESC")
    Page<Lead> findAllByStatusPaged(@Param("status") Lead.LeadStatus status, Pageable pageable);

    @Query("SELECT new com.chatcrmlite.backend.dto.RevenueReportDTO(" +
           "COALESCE(SUM(l.dealValue), 0), " +
           "COALESCE(SUM(CASE WHEN l.paymentStatus = 'PAID' THEN l.dealValue ELSE 0 END), 0), " +
           "COALESCE(SUM(CASE WHEN l.paymentStatus IN ('PENDING', 'PARTIAL') THEN l.dealValue ELSE 0 END), 0), " +
           "COUNT(CASE WHEN l.dealValue IS NOT NULL THEN 1 END), " +
           "COUNT(CASE WHEN l.paymentStatus = 'PAID' THEN 1 END), " +
           "COUNT(CASE WHEN l.paymentStatus IN ('PENDING', 'PARTIAL') THEN 1 END), " +
           "'INR') " +
           "FROM Lead l")
    com.chatcrmlite.backend.dto.RevenueReportDTO calculateTenantRevenueReport();

    long countByStatus(Lead.LeadStatus status);

    @Query("SELECT COUNT(l) FROM Lead l WHERE l.tenant.id = :tenantId")
    long countByTenantId(@Param("tenantId") UUID tenantId);
}
