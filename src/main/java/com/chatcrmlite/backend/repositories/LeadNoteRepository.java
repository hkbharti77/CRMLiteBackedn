package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.LeadNote;
import com.chatcrmlite.backend.models.Tenant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface LeadNoteRepository extends JpaRepository<LeadNote, UUID> {
    Page<LeadNote> findByLeadAndTenantAndDeletedFalseOrderByCreatedAtDesc(Lead lead, Tenant tenant, Pageable pageable);
    Optional<LeadNote> findByIdAndLeadAndTenantAndDeletedFalse(UUID id, Lead lead, Tenant tenant);
}
