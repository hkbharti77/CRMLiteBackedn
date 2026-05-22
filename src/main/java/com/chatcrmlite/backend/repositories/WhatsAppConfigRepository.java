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
     * Finds config by phone number ID, eagerly joining tenant and its users
     * so that config.getUser() works outside a transaction (e.g. in async workers).
     */
    @Query("SELECT w FROM WhatsAppConfig w JOIN FETCH w.tenant t JOIN FETCH t.users WHERE w.phoneNumberId = :phoneNumberId")
    Optional<WhatsAppConfig> findByPhoneNumberId(@Param("phoneNumberId") String phoneNumberId);

    /**
     * Finds config by the owner user's ID, eagerly joining tenant and users.
     * NOTE: :userId is the User.id (UUID), not the Tenant.id.
     * The second JOIN FETCH ensures the User.tenant back-reference is initialized,
     * preventing LazyInitializationException when owner.getBusinessSubType() is called
     * after the session closes (e.g. in async worker threads).
     */
    @Query("SELECT w FROM WhatsAppConfig w JOIN FETCH w.tenant t JOIN FETCH t.users u WHERE u.id = :userId")
    Optional<WhatsAppConfig> findByUserId(@Param("userId") UUID userId);

    @Query("SELECT w FROM WhatsAppConfig w JOIN FETCH w.tenant t JOIN FETCH t.users WHERE w.tenant.id = :tenantId")
    Optional<WhatsAppConfig> findByTenantId(@Param("tenantId") UUID tenantId);

    /**
     * Returns just the owner user's ID for a given phone number ID.
     * Used in async workers to avoid LazyInitializationException.
     */
    @Query("SELECT u.id FROM WhatsAppConfig w JOIN w.tenant t JOIN t.users u WHERE w.phoneNumberId = :phoneNumberId AND u.role = 'OWNER'")
    Optional<UUID> findOwnerIdByPhoneNumberId(@Param("phoneNumberId") String phoneNumberId);

    @Cacheable(value = "whatsapp_verify_tokens", key = "#verifyToken")
    boolean existsByVerifyToken(String verifyToken);

    @Override
    @CacheEvict(value = "whatsapp_verify_tokens", allEntries = true)
    <S extends WhatsAppConfig> S save(S entity);
}
