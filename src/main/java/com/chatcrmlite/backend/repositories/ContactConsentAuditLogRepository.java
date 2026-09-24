package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.ContactConsentAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ContactConsentAuditLogRepository extends JpaRepository<ContactConsentAuditLog, UUID> {

    List<ContactConsentAuditLog> findByTenantIdAndContactIdOrderByTimestampDesc(UUID tenantId, UUID contactId);

    List<ContactConsentAuditLog> findByContactIdOrderByTimestampDesc(UUID contactId);
}
