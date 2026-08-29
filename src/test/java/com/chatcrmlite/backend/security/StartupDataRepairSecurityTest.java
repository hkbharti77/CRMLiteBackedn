package com.chatcrmlite.backend.security;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.TenantRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.bootstrap.TenantDataRepairRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class StartupDataRepairSecurityTest {

    @Autowired
    private TenantDataRepairRunner runner;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ContactRepository contactRepository;

    @Autowired
    private EntityManager entityManager;

    private Tenant tenantA;
    private Tenant tenantB;
    private User ownerA;
    private User ownerB;

    @BeforeEach
    void setUp() {
        contactRepository.deleteAll();
        userRepository.deleteAll();
        tenantRepository.deleteAll();

        // 1. Setup Tenant A
        tenantA = new Tenant();
        tenantA.setBusinessName("Tenant A");
        tenantA = tenantRepository.save(tenantA);

        ownerA = new User();
        ownerA.setEmail("admin_a@tenantA.com");
        ownerA.setDisplayName("Admin A");
        ownerA.setRole(User.Role.ADMIN);
        ownerA.setTenant(tenantA);
        ownerA = userRepository.save(ownerA);

        // 2. Setup Tenant B
        tenantB = new Tenant();
        tenantB.setBusinessName("Tenant B");
        tenantB = tenantRepository.save(tenantB);

        ownerB = new User();
        ownerB.setEmail("admin_b@tenantB.com");
        ownerB.setDisplayName("Admin B");
        ownerB.setRole(User.Role.ADMIN);
        ownerB.setTenant(tenantB);
        ownerB = userRepository.save(ownerB);
    }

    @Test
    void testTenantAAndTenantBDataRemainsIsolated() throws Exception {
        // Setup properly isolated data
        Contact contactA = new Contact();
        contactA.setName("Contact A");
        contactA.setWaId("111111111");
        contactA.setTenant(tenantA);
        contactA.setOwner(ownerA);
        contactRepository.save(contactA);

        Contact contactB = new Contact();
        contactB.setName("Contact B");
        contactB.setWaId("222222222");
        contactB.setTenant(tenantB);
        contactB.setOwner(ownerB);
        contactRepository.save(contactB);

        entityManager.flush();
        entityManager.clear();

        // Run the repair
        runner.run(null);

        // Data should remain intact
        Contact updatedA = contactRepository.findById(contactA.getId()).orElseThrow();
        Contact updatedB = contactRepository.findById(contactB.getId()).orElseThrow();

        assertThat(updatedA.getTenant().getId()).isEqualTo(tenantA.getId());
        assertThat(updatedA.getOwner().getId()).isEqualTo(ownerA.getId());

        assertThat(updatedB.getTenant().getId()).isEqualTo(tenantB.getId());
        assertThat(updatedB.getOwner().getId()).isEqualTo(ownerB.getId());
    }

    @Test
    void testLegitimateSameTenantRepairWorks() throws Exception {
        // Missing owner, but valid tenant (derive owner from tenant's admin)
        Contact missingOwner = new Contact();
        missingOwner.setName("Missing Owner");
        missingOwner.setWaId("555555555");
        missingOwner.setTenant(tenantB);
        Contact savedMissingOwner = contactRepository.save(missingOwner);

        entityManager.flush();
        entityManager.clear();

        runner.run(null);

        Contact updatedMissingOwner = contactRepository.findById(savedMissingOwner.getId()).orElseThrow();
        assertThat(updatedMissingOwner.getTenant().getId()).isEqualTo(tenantB.getId());
        assertThat(updatedMissingOwner.getOwner()).isNotNull();
        assertThat(updatedMissingOwner.getOwner().getId()).isEqualTo(ownerB.getId());
    }
}
