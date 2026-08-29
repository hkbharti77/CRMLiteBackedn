package com.chatcrmlite.backend.security;

import com.chatcrmlite.backend.models.*;
import com.chatcrmlite.backend.repositories.*;
import com.chatcrmlite.backend.services.ActivityLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class ActivityLogEntityTimelineSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ContactRepository contactRepository;

    @Autowired
    private ActivityLogRepository activityLogRepository;

    @Autowired
    private ActivityLogService activityLogService;

    private User tenantAUser;
    private User tenantBUser;
    private UUID targetEntityId;

    @BeforeEach
    void setUp() {
        activityLogRepository.deleteAll();
        contactRepository.deleteAll();
        userRepository.deleteAll();
        tenantRepository.deleteAll();

        // 1. Setup Tenant A and User
        Tenant tenantA = new Tenant();
        tenantA.setBusinessName("Tenant A");
        tenantA = tenantRepository.save(tenantA);

        tenantAUser = new User();
        tenantAUser.setEmail("user_a@tenantA.com");
        tenantAUser.setDisplayName("User A");
        tenantAUser.setRole(User.Role.AGENT);
        tenantAUser.setTenant(tenantA);
        tenantAUser = userRepository.save(tenantAUser);

        // 2. Setup Tenant B and User
        Tenant tenantB = new Tenant();
        tenantB.setBusinessName("Tenant B");
        tenantB = tenantRepository.save(tenantB);

        tenantBUser = new User();
        tenantBUser.setEmail("user_b@tenantB.com");
        tenantBUser.setDisplayName("User B");
        tenantBUser.setRole(User.Role.AGENT);
        tenantBUser.setTenant(tenantB);
        tenantBUser = userRepository.save(tenantBUser);

        // 3. Create a dummy contact for Tenant A
        Contact contact = new Contact();
        contact.setOwner(tenantAUser);
        contact.setName("Test Contact A");
        contact.setWaId("1234567890");
        contact = contactRepository.save(contact);

        targetEntityId = UUID.randomUUID();

        // 4. Create an Activity Log for an entity owned by Tenant A
        ActivityLog log = ActivityLog.builder()
                .owner(tenantAUser)
                .contact(contact)
                .entityType(ActivityLog.TYPE_LEAD)
                .entityId(targetEntityId)
                .activityType(ActivityLog.LEAD_CREATED)
                .source("TEST")
                .summary("Test Lead created")
                .build();
        
        activityLogRepository.save(log);
    }

    @Test
    @WithMockUser(username = "user_a@tenantA.com")
    void testSameTenantAccessSucceeds() throws Exception {
        mockMvc.perform(get("/api/v1/activity-logs/entity/" + ActivityLog.TYPE_LEAD + "/" + targetEntityId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].summary", is("Test Lead created")));
    }

    @Test
    @WithMockUser(username = "user_b@tenantB.com")
    void testCrossTenantAccessIsRejectedOrEmpty() throws Exception {
        // Cross-tenant access should return 200 OK with empty list (safe behavior that doesn't leak existence)
        mockMvc.perform(get("/api/v1/activity-logs/entity/" + ActivityLog.TYPE_LEAD + "/" + targetEntityId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @WithMockUser(username = "user_a@tenantA.com")
    void testNonExistentEntityReturnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/v1/activity-logs/entity/" + ActivityLog.TYPE_LEAD + "/" + UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void testUnauthenticatedRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/activity-logs/entity/" + ActivityLog.TYPE_LEAD + "/" + targetEntityId))
                .andExpect(status().isForbidden());
    }
}
