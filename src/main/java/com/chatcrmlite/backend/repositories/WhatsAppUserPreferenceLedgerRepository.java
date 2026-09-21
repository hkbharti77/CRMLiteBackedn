package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.whatsapp.WhatsAppUserPreferenceLedger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface WhatsAppUserPreferenceLedgerRepository extends JpaRepository<WhatsAppUserPreferenceLedger, UUID> {

    @Query("SELECT CASE WHEN COUNT(l) > 0 THEN true ELSE false END FROM WhatsAppUserPreferenceLedger l WHERE l.tenant.id = :tenantId AND l.canonicalFingerprint = :fingerprint")
    boolean existsByTenantIdAndCanonicalFingerprint(
            @Param("tenantId") UUID tenantId,
            @Param("fingerprint") String fingerprint
    );

    @Query("SELECT l FROM WhatsAppUserPreferenceLedger l WHERE l.tenant.id = :tenantId AND l.canonicalFingerprint = :fingerprint")
    Optional<WhatsAppUserPreferenceLedger> findByTenantIdAndCanonicalFingerprint(
            @Param("tenantId") UUID tenantId,
            @Param("fingerprint") String fingerprint
    );
}
