package com.chatcrmlite.backend.security;

import com.chatcrmlite.backend.models.BusinessCategory;
import com.chatcrmlite.backend.models.BusinessSubCategory;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.BusinessCategoryRepository;
import com.chatcrmlite.backend.repositories.BusinessSubCategoryRepository;
import com.chatcrmlite.backend.repositories.TenantRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
public class BusinessCategorySecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BusinessCategoryRepository categoryRepository;

    @Autowired
    private BusinessSubCategoryRepository subCategoryRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private User superAdmin;
    private User owner;
    private User admin;
    private User agent;
    private BusinessCategory existingCategory;
    private BusinessSubCategory existingSubCategory;

    @BeforeEach
    void setUp() {
        Tenant tenant = new Tenant();
        tenant.setBusinessName("Tenant A");
        tenant = tenantRepository.save(tenant);

        User sa = new User();
        sa.setEmail("superadmin@example.com");
        sa.setDisplayName("Super Admin");
        sa.setPassword("pass");
        sa.setRole(User.Role.SUPER_ADMIN);
        sa.setTenant(tenant);
        superAdmin = userRepository.save(sa);

        User ow = new User();
        ow.setEmail("owner@example.com");
        ow.setDisplayName("Owner");
        ow.setPassword("pass");
        ow.setRole(User.Role.OWNER);
        ow.setTenant(tenant);
        owner = userRepository.save(ow);

        User ad = new User();
        ad.setEmail("admin@example.com");
        ad.setDisplayName("Admin");
        ad.setPassword("pass");
        ad.setRole(User.Role.ADMIN);
        ad.setTenant(tenant);
        admin = userRepository.save(ad);

        User ag = new User();
        ag.setEmail("agent@example.com");
        ag.setDisplayName("Agent");
        ag.setPassword("pass");
        ag.setRole(User.Role.AGENT);
        ag.setTenant(tenant);
        agent = userRepository.save(ag);

        existingCategory = new BusinessCategory();
        existingCategory.setName("Existing Category");
        existingCategory = categoryRepository.save(existingCategory);

        existingSubCategory = new BusinessSubCategory();
        existingSubCategory.setName("Existing Sub");
        existingSubCategory.setCategory(existingCategory);
        existingSubCategory = subCategoryRepository.save(existingSubCategory);
    }

    @Test
    @WithMockUser(username = "superadmin@example.com")
    void superAdminCanCreateCategory() throws Exception {
        Map<String, String> req = new HashMap<>();
        req.put("name", "New Category");

        mockMvc.perform(post("/api/v1/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());
        
        assertTrue(categoryRepository.existsByName("New Category"));
    }

    @Test
    @WithMockUser(username = "superadmin@example.com")
    void superAdminCanUpdateCategory() throws Exception {
        Map<String, String> req = new HashMap<>();
        req.put("name", "Updated Category");

        mockMvc.perform(put("/api/v1/categories/" + existingCategory.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "superadmin@example.com")
    void superAdminCanDeleteCategory() throws Exception {
        BusinessCategory toDelete = new BusinessCategory();
        toDelete.setName("To Delete");
        toDelete = categoryRepository.save(toDelete);
        
        mockMvc.perform(delete("/api/v1/categories/" + toDelete.getId()))
                .andExpect(status().isOk());
        
        assertFalse(categoryRepository.existsById(toDelete.getId()));
    }

    @Test
    @WithMockUser(username = "owner@example.com")
    void ownerCannotCreateUpdateOrDeleteCategory() throws Exception {
        Map<String, String> req = new HashMap<>();
        req.put("name", "Owner Category");

        mockMvc.perform(post("/api/v1/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
        assertFalse(categoryRepository.existsByName("Owner Category"));

        mockMvc.perform(put("/api/v1/categories/" + existingCategory.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/v1/categories/" + existingCategory.getId()))
                .andExpect(status().isForbidden());
        assertTrue(categoryRepository.existsById(existingCategory.getId()));
    }

    @Test
    @WithMockUser(username = "admin@example.com")
    void adminCannotCreateUpdateOrDeleteCategory() throws Exception {
        Map<String, String> req = new HashMap<>();
        req.put("name", "Admin Category");

        mockMvc.perform(post("/api/v1/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/v1/categories/" + existingCategory.getId()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "agent@example.com")
    void agentCannotCreateUpdateOrDeleteCategory() throws Exception {
        Map<String, String> req = new HashMap<>();
        req.put("name", "Agent Category");

        mockMvc.perform(post("/api/v1/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    void unauthenticatedRequestsAreRejected() throws Exception {
        Map<String, String> req = new HashMap<>();
        req.put("name", "Unauth Category");

        mockMvc.perform(post("/api/v1/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
        assertFalse(categoryRepository.existsByName("Unauth Category"));
    }
}
