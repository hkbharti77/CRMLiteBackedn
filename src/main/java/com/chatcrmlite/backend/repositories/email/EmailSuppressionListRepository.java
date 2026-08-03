package com.chatcrmlite.backend.repositories.email;

import com.chatcrmlite.backend.models.email.EmailSuppressionList;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmailSuppressionListRepository extends JpaRepository<EmailSuppressionList, UUID> {
    Optional<EmailSuppressionList> findByTenantIdAndEmail(UUID tenantId, String email);
    boolean existsByTenantIdAndEmail(UUID tenantId, String email);
}
