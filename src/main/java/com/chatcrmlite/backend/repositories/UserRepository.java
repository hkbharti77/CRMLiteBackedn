package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.User.PlanType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
    
    java.util.List<User> findAllByTenant(com.chatcrmlite.backend.models.Tenant tenant);

    @Query("SELECT u FROM User u LEFT JOIN FETCH u.tenant WHERE u.tenant = :tenant")
    java.util.List<User> findAllByTenantWithTenant(@Param("tenant") com.chatcrmlite.backend.models.Tenant tenant);

    /**
     * Fetches user with tenant eagerly loaded to avoid LazyInitializationException.
     * Use this method when you need to access tenant properties outside a transaction.
     */
    @Query("SELECT u FROM User u LEFT JOIN FETCH u.tenant WHERE u.email = :email")
    Optional<User> findByEmailWithTenant(@Param("email") String email);

    /**
     * Fetches the plan type directly from the tenant without triggering
     * lazy initialization of User.tenant. Safe to call outside a transaction.
     * 
     * Returns as String to avoid casting issues in async contexts.
     * Using native query to ensure String return type.
     */
    @Query(value = "SELECT t.plan_type FROM app_users u JOIN tenants t ON u.tenant_id = t.id WHERE u.id = :userId", nativeQuery = true)
    Optional<String> findPlanTypeByUserId(@Param("userId") UUID userId);

    /**
     * Fetches the plan type directly using the tenant ID.
     */
    @Query(value = "SELECT plan_type FROM tenants WHERE id = :tenantId", nativeQuery = true)
    Optional<String> findPlanTypeByTenantId(@Param("tenantId") UUID tenantId);

    /**
     * Check if another tenant (not this one) uses the same 4-character business name prefix.
     * Used for collision detection in reference number generation.
     * Returns true if collision exists, false otherwise.
     */
    @Query(value = "SELECT EXISTS(SELECT 1 FROM tenants t JOIN app_users u ON t.id = u.tenant_id WHERE UPPER(SUBSTRING(t.business_name, 1, 4)) = :prefix AND u.id != :userId)", nativeQuery = true)
    boolean existsByBusinessNamePrefixAndNotId(@Param("prefix") String prefix, @Param("userId") UUID userId);

    @Query("SELECT COUNT(u) FROM User u WHERE u.tenant.id = :tenantId")
    long countByTenantId(@Param("tenantId") UUID tenantId);

    @Query(value = "SELECT id FROM app_users WHERE tenant_id = :tenantId LIMIT 1", nativeQuery = true)
    Optional<UUID> findFirstUserIdByTenantId(@Param("tenantId") UUID tenantId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.tenant = :tenant AND u.role = :role AND u.accountStatus = 'ACTIVE' AND u.availabilityStatus = 'AVAILABLE' AND (u.lastSeenAt IS NULL OR u.lastSeenAt >= :threshold) ORDER BY u.createdAt ASC")
    java.util.List<User> findCandidateStaffWithLock(@Param("tenant") com.chatcrmlite.backend.models.Tenant tenant, @Param("role") User.Role role, @Param("threshold") java.time.LocalDateTime threshold);

    @Query("SELECT u FROM User u WHERE u.tenant = :tenant AND u.role IN :roles AND u.accountStatus = 'ACTIVE'")
    java.util.List<User> findStaffByTenantAndRoles(@Param("tenant") com.chatcrmlite.backend.models.Tenant tenant, @Param("roles") java.util.Collection<User.Role> roles);
}
