package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.LeadActivity;
import com.chatcrmlite.backend.models.Tenant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface LeadActivityRepository extends JpaRepository<LeadActivity, UUID> {
    Page<LeadActivity> findByLeadAndTenantOrderByCreatedAtDesc(Lead lead, Tenant tenant, Pageable pageable);
}
