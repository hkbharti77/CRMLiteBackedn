package com.chatcrmlite.backend.support;

import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.TenantRepository;

/**
 * Mixin-style helper for integration tests that need a persisted Tenant.
 *
 * Usage in a test:
 * <pre>
 *   &#64;Autowired TenantRepository tenantRepository;
 *
 *   &#64;BeforeEach void setUp() {
 *       Tenant tenant = TenantAwareTestSupport.createTenant(tenantRepository, "Test Business");
 *       testUser = TenantAwareTestSupport.buildUser("email@test.com", tenant);
 *       testUser = userRepository.save(testUser);
 *   }
 * </pre>
 */
public final class TenantAwareTestSupport {

    private TenantAwareTestSupport() {}

    /** Save a minimal Tenant and return it. */
    public static Tenant createTenant(TenantRepository repo, String businessName) {
        Tenant tenant = Tenant.builder()
                .businessName(businessName)
                .businessType("GENERAL")
                .businessSubType("GENERAL")
                .build();
        return repo.save(tenant);
    }

    /** Build a User that already has its Tenant set (so NOT NULL is satisfied on save). */
    public static User buildUser(String email, String businessName, String businessSubType, Tenant tenant) {
        return User.builder()
                .email(email)
                .password("test123")
                .businessName(businessName)
                .businessSubType(businessSubType)
                .tenant(tenant)
                .build();
    }
}
