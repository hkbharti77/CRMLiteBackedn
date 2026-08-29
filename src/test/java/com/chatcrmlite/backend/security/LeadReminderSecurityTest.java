package com.chatcrmlite.backend.security;

import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.Reminder;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.LeadRepository;
import com.chatcrmlite.backend.repositories.ReminderRepository;
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

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
public class LeadReminderSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LeadRepository leadRepository;

    @Autowired
    private ReminderRepository reminderRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private Tenant tenantA;
    private Tenant tenantB;
    private User agentA;
    private User adminA;
    private User agentB;
    private Lead leadA;
    private Lead leadB;
    private Reminder reminderA;

    @BeforeEach
    void setUp() {
        Tenant tA = new Tenant();
        tA.setBusinessName("Tenant A");
        tenantA = tenantRepository.save(tA);

        Tenant tB = new Tenant();
        tB.setBusinessName("Tenant B");
        tenantB = tenantRepository.save(tB);

        User aA = new User();
        aA.setEmail("agent_a@example.com");
        aA.setDisplayName("Agent A");
        aA.setPassword("pass");
        aA.setRole(User.Role.AGENT);
        aA.setTenant(tenantA);
        agentA = userRepository.save(aA);

        User adA = new User();
        adA.setEmail("admin_a@example.com");
        adA.setDisplayName("Admin A");
        adA.setPassword("pass");
        adA.setRole(User.Role.ADMIN);
        adA.setTenant(tenantA);
        adminA = userRepository.save(adA);

        User aB = new User();
        aB.setEmail("agent_b@example.com");
        aB.setDisplayName("Agent B");
        aB.setPassword("pass");
        aB.setRole(User.Role.AGENT);
        aB.setTenant(tenantB);
        agentB = userRepository.save(aB);

        leadA = new Lead();
        leadA.setLeadNumber("L-100");
        leadA.setTenant(tenantA);
        leadA.setOwner(agentA);
        leadA = leadRepository.save(leadA);

        leadB = new Lead();
        leadB.setLeadNumber("L-200");
        leadB.setTenant(tenantB);
        leadB.setOwner(agentB);
        leadB = leadRepository.save(leadB);

        reminderA = new Reminder(null, leadA, "Follow up", LocalDateTime.now().plusDays(1), false, agentA, LocalDateTime.now());
        reminderA = reminderRepository.save(reminderA);
    }

    @Test
    @WithMockUser(username = "agent_a@example.com")
    void testSameTenantReminderCreationSucceeds() throws Exception {
        String jsonPayload = """
            {
                "lead": { "id": "%s" },
                "message": "New Note"
            }
        """.formatted(leadA.getId());

        mockMvc.perform(post("/api/v1/reminders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("New Note"));
    }

    @Test
    @WithMockUser(username = "agent_b@example.com")
    void testCrossTenantReminderCreationIsBlocked() throws Exception {
        String jsonPayload = """
            {
                "lead": { "id": "%s" },
                "message": "Malicious Note"
            }
        """.formatted(leadA.getId());

        mockMvc.perform(post("/api/v1/reminders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonPayload))
                .andExpect(status().isInternalServerError());
    }

    @Test
    @WithMockUser(username = "agent_a@example.com")
    void testSameTenantReminderRetrievalSucceeds() throws Exception {
        mockMvc.perform(get("/api/v1/reminders/pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].message").value("Follow up"));
    }

    @Test
    @WithMockUser(username = "agent_b@example.com")
    void testCrossTenantReminderCompletionIsBlocked() throws Exception {
        mockMvc.perform(patch("/api/v1/reminders/" + reminderA.getId() + "/complete"))
                .andExpect(status().isInternalServerError());

        assertThat(reminderRepository.findById(reminderA.getId()).get().isCompleted()).isFalse();
    }

    @Test
    @WithMockUser(username = "admin_a@example.com")
    void testSameTenantAdminCanCompleteReminder() throws Exception {
        mockMvc.perform(patch("/api/v1/reminders/" + reminderA.getId() + "/complete"))
                .andExpect(status().isOk());

        assertThat(reminderRepository.findById(reminderA.getId()).get().isCompleted()).isTrue();
    }
}
