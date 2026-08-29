package com.chatcrmlite.backend.security;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.ConversationState;
import com.chatcrmlite.backend.models.SessionStatus;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.ConversationStateRepository;
import com.chatcrmlite.backend.repositories.TenantRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
public class FlowDebugSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ContactRepository contactRepository;

    @Autowired
    private ConversationStateRepository stateRepository;

    private Tenant tenantA;
    private Tenant tenantB;
    private User adminA;
    private User adminB;
    private Contact contactA;
    private ConversationState stateA;

    @BeforeEach
    void setUp() {
        Tenant tA = new Tenant();
        tA.setBusinessName("Tenant A");
        tenantA = tenantRepository.save(tA);

        Tenant tB = new Tenant();
        tB.setBusinessName("Tenant B");
        tenantB = tenantRepository.save(tB);

        User adA = new User();
        adA.setEmail("admin_a@example.com");
        adA.setDisplayName("Admin A");
        adA.setPassword("pass");
        adA.setRole(User.Role.ADMIN);
        adA.setTenant(tenantA);
        adminA = userRepository.save(adA);

        User adB = new User();
        adB.setEmail("admin_b@example.com");
        adB.setDisplayName("Admin B");
        adB.setPassword("pass");
        adB.setRole(User.Role.ADMIN);
        adB.setTenant(tenantB);
        adminB = userRepository.save(adB);

        contactA = new Contact();
        contactA.setName("Contact A");
        contactA.setWaId("111111111");
        contactA.setTenant(tenantA);
        contactA = contactRepository.save(contactA);

        stateA = new ConversationState();
        stateA.setContact(contactA);
        stateA.setFlowType(ConversationState.FlowType.LEAD_CAPTURE);
        stateA.setSessionStatus(SessionStatus.ACTIVE);
        stateA.setStateHistory("[{\"state\":\"START\"}]");
        stateA.setLastUpdatedAt(LocalDateTime.now());
        stateA.setLastActivityAt(LocalDateTime.now());
        stateA = stateRepository.save(stateA);
    }

    @Test
    @WithMockUser(username = "admin_a@example.com")
    void testSameTenantDebugHistoryAccessSucceeds() throws Exception {
        mockMvc.perform(get("/api/v1/flow-debug/contact/" + contactA.getId() + "/history"))
                .andExpect(status().isOk())
                .andExpect(content().string("[{\"state\":\"START\"}]"));
    }

    @Test
    @WithMockUser(username = "admin_b@example.com")
    void testCrossTenantContactAccessReturns404() throws Exception {
        // Must fail closed with 404 to not disclose existence
        mockMvc.perform(get("/api/v1/flow-debug/contact/" + contactA.getId() + "/history"))
                .andExpect(status().isNotFound());
    }

    @Test
    void testUnauthenticatedRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/flow-debug/contact/" + contactA.getId() + "/history"))
                .andExpect(status().isForbidden());
    }
}
