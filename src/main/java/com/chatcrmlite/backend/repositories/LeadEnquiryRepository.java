package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.LeadEnquiry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LeadEnquiryRepository extends JpaRepository<LeadEnquiry, UUID> {

    /** All enquiries for a lead, oldest first (chronological timeline) */
    List<LeadEnquiry> findAllByLeadOrderByCreatedAtAsc(Lead lead);

    /** All enquiries for a lead, newest first (for recent activity views) */
    List<LeadEnquiry> findAllByLeadOrderByCreatedAtDesc(Lead lead);

    /** All enquiries for a lead by lead ID — avoids loading the Lead entity */
    @Query("SELECT e FROM LeadEnquiry e WHERE e.lead.id = :leadId ORDER BY e.createdAt ASC")
    List<LeadEnquiry> findAllByLeadId(@Param("leadId") UUID leadId);

    /** Find a single enquiry belonging to a specific lead (tenant-scoped check) */
    Optional<LeadEnquiry> findByIdAndLead(UUID id, Lead lead);

    /** Count enquiries per lead — used for analytics */
    long countByLead(Lead lead);

    /** Delete all enquiries when a lead is hard-deleted (cascades from Lead.@OneToMany but explicit for safety) */
    @Modifying
    @Query("DELETE FROM LeadEnquiry e WHERE e.lead.id = :leadId")
    void deleteAllByLeadId(@Param("leadId") UUID leadId);
}
