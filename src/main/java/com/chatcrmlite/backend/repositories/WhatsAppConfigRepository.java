package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.WhatsAppConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface WhatsAppConfigRepository extends JpaRepository<WhatsAppConfig, UUID> {

    /**
     * Finds config by phone number ID (or embedded phone ID), eagerly joining tenant and its users
     * so that config.getUser() works outside a transaction (e.g. in async workers).
     * Uses DISTINCT and LEFT JOIN FETCH to handle tenants with multiple users or 0 users.
     */
    @Query("SELECT DISTINCT w FROM WhatsAppConfig w LEFT JOIN FETCH w.tenant t LEFT JOIN FETCH t.users WHERE TRIM(w.phoneNumberId) = TRIM(:phoneNumberId) OR TRIM(w.embeddedPhoneId) = TRIM(:phoneNumberId)")
    Optional<WhatsAppConfig> findByPhoneNumberId(@Param("phoneNumberId") String phoneNumberId);

    /**
     * Finds config by the owner user's ID, eagerly joining tenant and users.
     * NOTE: :userId is the User.id (UUID), not the Tenant.id.
     */
    @Query("SELECT DISTINCT w FROM WhatsAppConfig w LEFT JOIN FETCH w.tenant t LEFT JOIN FETCH t.users u WHERE u.id = :userId")
    Optional<WhatsAppConfig> findByUserId(@Param("userId") UUID userId);

    /**
     * Finds config by tenant ID, eagerly joining tenant and users.
     * Uses DISTINCT and LEFT JOIN FETCH to prevent NonUniqueResultException when multiple users exist.
     */
    @Query("SELECT DISTINCT w FROM WhatsAppConfig w LEFT JOIN FETCH w.tenant t LEFT JOIN FETCH t.users WHERE w.tenant.id = :tenantId")
    Optional<WhatsAppConfig> findByTenantId(@Param("tenantId") UUID tenantId);

    /**
     * Returns just the owner user's ID for a given phone number ID.
     */
    @Query("SELECT u.id FROM WhatsAppConfig w JOIN w.tenant t JOIN t.users u WHERE (TRIM(w.phoneNumberId) = TRIM(:phoneNumberId) OR TRIM(w.embeddedPhoneId) = TRIM(:phoneNumberId)) AND u.role = 'OWNER'")
    Optional<UUID> findOwnerIdByPhoneNumberId(@Param("phoneNumberId") String phoneNumberId);

    /**
     * Returns just the tenant ID for a given phone number ID (or embedded phone ID).
     */
    @Query("SELECT t.id FROM WhatsAppConfig w JOIN w.tenant t WHERE TRIM(w.phoneNumberId) = TRIM(:phoneNumberId) OR TRIM(w.embeddedPhoneId) = TRIM(:phoneNumberId)")
    Optional<UUID> findTenantIdByPhoneNumberId(@Param("phoneNumberId") String phoneNumberId);

    Optional<WhatsAppConfig> findByWabaId(String wabaId);

    @Cacheable(value = "whatsapp_verify_tokens", key = "#verifyToken")
    boolean existsByVerifyToken(String verifyToken);

    @Override
    @CacheEvict(value = "whatsapp_verify_tokens", allEntries = true)
    <S extends WhatsAppConfig> S save(S entity);
}
