package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.whatsapp.WhatsAppCallPermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface WhatsAppCallPermissionRepository extends JpaRepository<WhatsAppCallPermission, UUID> {

    @Query("SELECT p FROM WhatsAppCallPermission p WHERE p.tenant.id = :tenantId AND p.phoneNumberId = :phoneNumberId AND p.userWaId = :userWaId")
    Optional<WhatsAppCallPermission> findByTenantIdAndPhoneNumberIdAndUserWaId(
        @Param("tenantId") UUID tenantId,
        @Param("phoneNumberId") String phoneNumberId,
        @Param("userWaId") String userWaId
    );

    @Query("SELECT p FROM WhatsAppCallPermission p WHERE p.phoneNumberId = :phoneNumberId AND p.userWaId = :userWaId")
    Optional<WhatsAppCallPermission> findByPhoneNumberIdAndUserWaId(
        @Param("phoneNumberId") String phoneNumberId,
        @Param("userWaId") String userWaId
    );
}
