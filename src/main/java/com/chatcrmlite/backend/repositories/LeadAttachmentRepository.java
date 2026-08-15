package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.LeadAttachment;
import com.chatcrmlite.backend.models.Tenant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface LeadAttachmentRepository extends JpaRepository<LeadAttachment, UUID> {
    Page<LeadAttachment> findByLeadAndTenantAndDeletedFalseOrderByCreatedAtDesc(Lead lead, Tenant tenant, Pageable pageable);
    Optional<LeadAttachment> findByIdAndLeadAndTenantAndDeletedFalse(UUID id, Lead lead, Tenant tenant);
}
