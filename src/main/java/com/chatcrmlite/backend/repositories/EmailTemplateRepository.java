package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.EmailTemplate;
import com.chatcrmlite.backend.models.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmailTemplateRepository extends JpaRepository<EmailTemplate, UUID> {
    List<EmailTemplate> findAllByTenant(Tenant tenant);
    Optional<EmailTemplate> findByTenantAndInterestCategory(Tenant tenant, String interestCategory);
    Optional<EmailTemplate> findByTenantAndInterestCategoryIsNull(Tenant tenant);
}
