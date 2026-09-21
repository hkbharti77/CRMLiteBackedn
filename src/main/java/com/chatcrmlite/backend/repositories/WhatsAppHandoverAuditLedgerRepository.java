package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.whatsapp.WhatsAppHandoverAuditLedger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface WhatsAppHandoverAuditLedgerRepository extends JpaRepository<WhatsAppHandoverAuditLedger, UUID> {

    @Query("SELECT CASE WHEN COUNT(l) > 0 THEN true ELSE false END FROM WhatsAppHandoverAuditLedger l WHERE l.tenant.id = :tenantId AND l.fingerprint = :fingerprint")
    boolean existsByTenantIdAndFingerprint(
            @Param("tenantId") UUID tenantId,
            @Param("fingerprint") String fingerprint
    );

    @Query("SELECT l FROM WhatsAppHandoverAuditLedger l WHERE l.tenant.id = :tenantId AND l.fingerprint = :fingerprint")
    Optional<WhatsAppHandoverAuditLedger> findByTenantIdAndFingerprint(
            @Param("tenantId") UUID tenantId,
            @Param("fingerprint") String fingerprint
    );
}
