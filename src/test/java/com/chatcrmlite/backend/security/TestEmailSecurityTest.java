package com.chatcrmlite.backend.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
public class TestEmailSecurityTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    @DisplayName("Unauthenticated GET to /api/v1/test-emails/send-all is rejected")
    void testUnauthenticatedGet_IsDenied() throws Exception {
        mockMvc.perform(get("/api/v1/test-emails/send-all").with(anonymous()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Unauthenticated POST to /api/v1/test-emails/send-all is rejected")
    void testUnauthenticatedPost_IsDenied() throws Exception {
        mockMvc.perform(post("/api/v1/test-emails/send-all").with(anonymous()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Authenticated ADMIN request to /api/v1/test-emails/send-all is permitted")
    void testAdminAccess_IsPermitted() throws Exception {
        mockMvc.perform(get("/api/v1/test-emails/send-all").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());
    }
}
