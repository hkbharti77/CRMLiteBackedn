package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.whatsapp.WhatsAppPhoneNumberConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface WhatsAppPhoneNumberConfigRepository extends JpaRepository<WhatsAppPhoneNumberConfig, UUID> {

    @Query("SELECT c FROM WhatsAppPhoneNumberConfig c WHERE c.phoneNumberId = :phoneNumberId")
    Optional<WhatsAppPhoneNumberConfig> findByPhoneNumberId(@Param("phoneNumberId") String phoneNumberId);

    @Query("SELECT c FROM WhatsAppPhoneNumberConfig c WHERE c.tenant.id = :tenantId AND c.phoneNumberId = :phoneNumberId")
    Optional<WhatsAppPhoneNumberConfig> findByTenantIdAndPhoneNumberId(
            @Param("tenantId") UUID tenantId,
            @Param("phoneNumberId") String phoneNumberId
    );

    @Query("SELECT c FROM WhatsAppPhoneNumberConfig c WHERE c.tenant.id = :tenantId AND c.wabaId = :wabaId AND c.phoneNumberId = :phoneNumberId")
    Optional<WhatsAppPhoneNumberConfig> findByTenantIdAndWabaIdAndPhoneNumberId(
            @Param("tenantId") UUID tenantId,
            @Param("wabaId") String wabaId,
            @Param("phoneNumberId") String phoneNumberId
    );
}
