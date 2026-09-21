package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.whatsapp.ContactPhoneThreadControl;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ContactPhoneThreadControlRepository extends JpaRepository<ContactPhoneThreadControl, UUID> {

    @Query("SELECT c FROM ContactPhoneThreadControl c WHERE c.tenant.id = :tenantId AND c.contact.id = :contactId AND c.phoneNumberId = :phoneNumberId")
    Optional<ContactPhoneThreadControl> findByTenantIdAndContactIdAndPhoneNumberId(
            @Param("tenantId") UUID tenantId,
            @Param("contactId") UUID contactId,
            @Param("phoneNumberId") String phoneNumberId
    );
}
