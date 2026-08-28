package com.chatcrmlite.backend.services.livechat;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.LiveChatAssignmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
public class LiveChatAuthorizationServiceSecurityTest {

    @Mock
    private LiveChatAssignmentRepository assignmentRepository;

    @InjectMocks
    private LiveChatAuthorizationService authService;

    private Tenant tenantA;
    private Tenant tenantB;
    private User agentTenantA;
    private User agentTenantB;
    private User adminTenantA;
    private User ownerTenantA;
    private User userNoTenant;
    private Contact contactTenantA;
    private Contact contactTenantB;
    private Contact contactNoTenantWithOwnerA;
    private Contact contactNoTenantWithOwnerB;
    private Contact contactNoTenantNoOwner;

    @BeforeEach
    void setUp() {
        tenantA = new Tenant();
        tenantA.setId(UUID.randomUUID());
        tenantA.setBusinessName("Tenant A");

        tenantB = new Tenant();
        tenantB.setId(UUID.randomUUID());
        tenantB.setBusinessName("Tenant B");

        agentTenantA = User.builder()
                .id(UUID.randomUUID())
                .email("agentA@tenanta.com")
                .role(User.Role.AGENT)
                .tenant(tenantA)
                .build();

        agentTenantB = User.builder()
                .id(UUID.randomUUID())
                .email("agentB@tenantb.com")
                .role(User.Role.AGENT)
                .tenant(tenantB)
                .build();

        adminTenantA = User.builder()
                .id(UUID.randomUUID())
                .email("adminA@tenanta.com")
                .role(User.Role.ADMIN)
                .tenant(tenantA)
                .build();

        ownerTenantA = User.builder()
                .id(UUID.randomUUID())
                .email("ownerA@tenanta.com")
                .role(User.Role.OWNER)
                .tenant(tenantA)
                .build();

        userNoTenant = User.builder()
                .id(UUID.randomUUID())
                .email("notenant@example.com")
                .role(User.Role.ADMIN)
                .tenant(null)
                .build();

        contactTenantA = new Contact();
        contactTenantA.setId(UUID.randomUUID());
        contactTenantA.setTenant(tenantA);

        contactTenantB = new Contact();
        contactTenantB.setId(UUID.randomUUID());
        contactTenantB.setTenant(tenantB);

        contactNoTenantWithOwnerA = new Contact();
        contactNoTenantWithOwnerA.setId(UUID.randomUUID());
        contactNoTenantWithOwnerA.setTenant(null);
        contactNoTenantWithOwnerA.setOwner(ownerTenantA);

        contactNoTenantWithOwnerB = new Contact();
        contactNoTenantWithOwnerB.setId(UUID.randomUUID());
        contactNoTenantWithOwnerB.setTenant(null);
        User ownerB = User.builder().id(UUID.randomUUID()).role(User.Role.OWNER).tenant(tenantB).build();
        contactNoTenantWithOwnerB.setOwner(ownerB);

        contactNoTenantNoOwner = new Contact();
        contactNoTenantNoOwner.setId(UUID.randomUUID());
        contactNoTenantNoOwner.setTenant(null);
        contactNoTenantNoOwner.setOwner(null);
    }

    @Test
    @DisplayName("Security 1: Same-tenant user/contact -> allowed")
    void testSameTenantUserAndContactAllowed() {
        assertTrue(authService.isSameTenant(contactTenantA, agentTenantA));
        assertTrue(authService.canAccessContact(contactTenantA, agentTenantA));
        assertTrue(authService.canSendMessage(contactTenantA, agentTenantA));
        assertTrue(authService.canTakeover(contactTenantA, adminTenantA));
    }

    @Test
    @DisplayName("Security 2: Different-tenant user/contact -> denied")
    void testDifferentTenantUserAndContactDenied() {
        assertFalse(authService.isSameTenant(contactTenantA, agentTenantB));
        assertFalse(authService.isSameTenant(contactTenantB, agentTenantA));
        assertFalse(authService.canAccessContact(contactTenantA, agentTenantB));
        assertFalse(authService.canAccessContact(contactTenantB, agentTenantA));
        assertFalse(authService.canSendMessage(contactTenantA, agentTenantB));
        assertFalse(authService.canTakeover(contactTenantB, adminTenantA));
        assertFalse(authService.canTransfer(contactTenantB, agentTenantA));
        assertFalse(authService.canResolve(contactTenantB, agentTenantA));
    }

    @Test
    @DisplayName("Security 3: Contact tenant null -> denied unless verified owner tenant relationship is valid")
    void testContactTenantNullDeniedUnlessOwnerMatches() {
        // Valid owner matching tenant A -> Allowed
        assertTrue(authService.isSameTenant(contactNoTenantWithOwnerA, agentTenantA));
        assertTrue(authService.canAccessContact(contactNoTenantWithOwnerA, agentTenantA));

        // Owner belonging to tenant B -> Denied for tenant A agent
        assertFalse(authService.isSameTenant(contactNoTenantWithOwnerB, agentTenantA));
        assertFalse(authService.canAccessContact(contactNoTenantWithOwnerB, agentTenantA));

        // Owner is null -> Denied
        assertFalse(authService.isSameTenant(contactNoTenantNoOwner, agentTenantA));
        assertFalse(authService.canAccessContact(contactNoTenantNoOwner, agentTenantA));
    }

    @Test
    @DisplayName("Security 4: User tenant null -> denied")
    void testUserTenantNullDenied() {
        assertFalse(authService.isSameTenant(contactTenantA, userNoTenant));
        assertFalse(authService.canAccessContact(contactTenantA, userNoTenant));
        assertFalse(authService.canSendMessage(contactTenantA, userNoTenant));
        assertFalse(authService.canTakeover(contactTenantA, userNoTenant));
        assertFalse(authService.canTransfer(contactTenantA, userNoTenant));
        assertFalse(authService.canResolve(contactTenantA, userNoTenant));
    }

    @Test
    @DisplayName("Security 5: Both tenant relationships missing -> denied")
    void testBothTenantsMissingDenied() {
        assertFalse(authService.isSameTenant(contactNoTenantNoOwner, userNoTenant));
        assertFalse(authService.canAccessContact(contactNoTenantNoOwner, userNoTenant));
        assertFalse(authService.canSendMessage(contactNoTenantNoOwner, userNoTenant));
    }

    @Test
    @DisplayName("Security 6: Owner-based tenant fallback works only when owner positively proves same tenant")
    void testOwnerFallbackStrictlyEnforcesTenantMatch() {
        // Contact has owner in Tenant A -> matches Agent in Tenant A
        assertTrue(authService.isSameTenant(contactNoTenantWithOwnerA, agentTenantA));
        assertTrue(authService.isSameTenant(contactNoTenantWithOwnerA, adminTenantA));

        // Contact has owner in Tenant A -> does NOT match Agent in Tenant B
        assertFalse(authService.isSameTenant(contactNoTenantWithOwnerA, agentTenantB));

        // Contact with null tenant and owner with null tenant -> Denied
        User ownerNoTenant = User.builder().id(UUID.randomUUID()).role(User.Role.OWNER).tenant(null).build();
        Contact contactOwnerNoTenant = new Contact();
        contactOwnerNoTenant.setId(UUID.randomUUID());
        contactOwnerNoTenant.setTenant(null);
        contactOwnerNoTenant.setOwner(ownerNoTenant);

        assertFalse(authService.isSameTenant(contactOwnerNoTenant, agentTenantA));
        assertFalse(authService.canAccessContact(contactOwnerNoTenant, agentTenantA));
    }

    @Test
    @DisplayName("Security 7: Unknown/unresolved relationships -> denied (null checks fail closed)")
    void testNullEntitiesFailClosed() {
        assertFalse(authService.isSameTenant(null, agentTenantA));
        assertFalse(authService.isSameTenant(contactTenantA, null));
        assertFalse(authService.isSameTenant(null, null));

        assertFalse(authService.canAccessContact(null, agentTenantA));
        assertFalse(authService.canAccessContact(contactTenantA, null));
        assertFalse(authService.canSendMessage(null, agentTenantA));
        assertFalse(authService.canSendMessage(contactTenantA, null));
        assertFalse(authService.canTakeover(null, adminTenantA));
        assertFalse(authService.canTakeover(contactTenantA, null));
        assertFalse(authService.canTransfer(null, agentTenantA));
        assertFalse(authService.canTransfer(contactTenantA, null));
        assertFalse(authService.canResolve(null, agentTenantA));
        assertFalse(authService.canResolve(contactTenantA, null));
    }

    @Test
    @DisplayName("Security 8: Role and assignment logic behaves correctly for same-tenant requests")
    void testRoleAndAssignmentChecks() {
        // Contact assigned to agentA
        contactTenantA.setAssignedAgent(agentTenantA);

        // AgentA can access, send, transfer, resolve
        assertTrue(authService.canAccessContact(contactTenantA, agentTenantA));
        assertTrue(authService.canSendMessage(contactTenantA, agentTenantA));
        assertTrue(authService.canTransfer(contactTenantA, agentTenantA));
        assertTrue(authService.canResolve(contactTenantA, agentTenantA));
        assertFalse(authService.canTakeover(contactTenantA, agentTenantA)); // Agent cannot takeover

        // Another agent in same tenant (agentA2)
        User agentA2 = User.builder().id(UUID.randomUUID()).email("agentA2@tenanta.com").role(User.Role.AGENT).tenant(tenantA).build();
        assertFalse(authService.canAccessContact(contactTenantA, agentA2));
        assertFalse(authService.canSendMessage(contactTenantA, agentA2));
        assertFalse(authService.canTransfer(contactTenantA, agentA2));
        assertFalse(authService.canResolve(contactTenantA, agentA2));

        // Admin/Owner in same tenant can access, takeover, transfer, resolve
        assertTrue(authService.canAccessContact(contactTenantA, adminTenantA));
        assertTrue(authService.canSendMessage(contactTenantA, adminTenantA));
        assertTrue(authService.canTakeover(contactTenantA, adminTenantA));
        assertTrue(authService.canTransfer(contactTenantA, adminTenantA));
        assertTrue(authService.canResolve(contactTenantA, adminTenantA));

        assertTrue(authService.canTakeover(contactTenantA, ownerTenantA));
    }
}
