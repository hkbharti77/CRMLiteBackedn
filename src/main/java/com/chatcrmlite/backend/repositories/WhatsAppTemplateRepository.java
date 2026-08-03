package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.WhatsAppTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WhatsAppTemplateRepository extends JpaRepository<WhatsAppTemplate, UUID> {

    List<WhatsAppTemplate> findAllByOwner(User owner);

    @Query("SELECT t FROM WhatsAppTemplate t WHERE t.owner.tenant.id = :tenantId")
    List<WhatsAppTemplate> findAllByTenantId(@Param("tenantId") UUID tenantId);

    Optional<WhatsAppTemplate> findByNameAndOwner(String name, User owner);

    @Query("SELECT t FROM WhatsAppTemplate t WHERE t.name = :name AND t.owner.tenant.id = :tenantId")
    Optional<WhatsAppTemplate> findByNameAndTenantId(@Param("name") String name, @Param("tenantId") UUID tenantId);

    Optional<WhatsAppTemplate> findFirstByName(String name);

    Optional<WhatsAppTemplate> findFirstByMetaTemplateId(String metaTemplateId);

    void deleteByNameAndOwner(String name, User owner);
}
