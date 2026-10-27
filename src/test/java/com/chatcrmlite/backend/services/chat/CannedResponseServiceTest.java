package com.chatcrmlite.backend.services.chat;

import com.chatcrmlite.backend.dto.chat.CannedResponseDto;
import com.chatcrmlite.backend.dto.chat.CreateCannedResponseRequest;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.chat.CannedResponse;
import com.chatcrmlite.backend.repositories.chat.CannedResponseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CannedResponseServiceTest {

    @Mock
    private CannedResponseRepository repository;

    @InjectMocks
    private CannedResponseService service;

    private Tenant tenant;
    private User user;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        tenant = new Tenant();
        tenant.setId(tenantId);
        tenant.setBusinessName("Acme Corp");

        user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("agent@acme.com");
        user.setDisplayName("Alice Agent");
        user.setTenant(tenant);
    }

    @Test
    @DisplayName("Create canned response - Success")
    void testCreate_Success() {
        CreateCannedResponseRequest req = CreateCannedResponseRequest.builder()
                .title("Greeting")
                .shortcut("/greet")
                .content("Hello {{contact.name}}, welcome to {{company.name}}!")
                .category("GREETINGS")
                .isShared(true)
                .build();

        when(repository.existsByTenantIdAndShortcutIgnoreCase(tenantId, "greet")).thenReturn(false);
        when(repository.save(any(CannedResponse.class))).thenAnswer(invocation -> {
            CannedResponse saved = invocation.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });

        CannedResponseDto result = service.create(req, user);

        assertNotNull(result);
        assertEquals("greet", result.getShortcut());
        assertEquals("Greeting", result.getTitle());
        verify(repository).save(any(CannedResponse.class));
    }

    @Test
    @DisplayName("Create canned response - Duplicate shortcut throws exception")
    void testCreate_DuplicateShortcut_Throws() {
        CreateCannedResponseRequest req = CreateCannedResponseRequest.builder()
                .title("Pricing Info")
                .shortcut("pricing")
                .content("Here is our pricing.")
                .build();

        when(repository.existsByTenantIdAndShortcutIgnoreCase(tenantId, "pricing")).thenReturn(true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.create(req, user));
        assertTrue(ex.getMessage().contains("already exists"));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Update canned response - Success")
    void testUpdate_Success() {
        UUID responseId = UUID.randomUUID();
        CannedResponse existing = new CannedResponse(responseId, tenant, "Old Title", "old", "Old content", "GENERAL", true, 5L, user);

        CreateCannedResponseRequest req = CreateCannedResponseRequest.builder()
                .title("Updated Title")
                .shortcut("newshortcut")
                .content("New content")
                .category("SUPPORT")
                .isShared(true)
                .build();

        when(repository.findById(responseId)).thenReturn(Optional.of(existing));
        when(repository.existsByTenantIdAndShortcutIgnoreCaseAndIdNot(tenantId, "newshortcut", responseId)).thenReturn(false);
        when(repository.save(any(CannedResponse.class))).thenAnswer(inv -> inv.getArgument(0));

        CannedResponseDto result = service.update(responseId, req, user);

        assertNotNull(result);
        assertEquals("newshortcut", result.getShortcut());
        assertEquals("Updated Title", result.getTitle());
        assertEquals("SUPPORT", result.getCategory());
    }

    @Test
    @DisplayName("Interpolate variables - Contact, Agent, and Company names")
    void testInterpolatePlaceholders() {
        Contact contact = new Contact();
        contact.setName("John Doe");
        contact.setWaId("+1234567890");

        String template = "Hello {{contact.name}}! My name is {{agent.name}} from {{company.name}}. We have your number as {{contact.phone}}.";
        String result = service.interpolatePlaceholders(template, contact, user);

        assertEquals("Hello John Doe! My name is Alice Agent from Acme Corp. We have your number as +1234567890.", result);
    }

    @Test
    @DisplayName("Interpolate variables - Fallbacks when contact is null")
    void testInterpolatePlaceholders_NullContact_Fallback() {
        String template = "Hello {{contact.name}} from {{company.name}}!";
        String result = service.interpolatePlaceholders(template, null, user);

        assertEquals("Hello Customer from Acme Corp!", result);
    }

    @Test
    @DisplayName("Record usage increments count")
    void testRecordUsage() {
        UUID id = UUID.randomUUID();
        CannedResponse existing = new CannedResponse(id, tenant, "FAQ", "faq", "Answers", "GENERAL", true, 10L, user);

        when(repository.findById(id)).thenReturn(Optional.of(existing));

        service.recordUsage(id, tenantId);

        assertEquals(11L, existing.getUsageCount());
        verify(repository).save(existing);
    }

    @Test
    @DisplayName("Search canned responses")
    void testSearch() {
        when(repository.searchByKeyword(tenantId, "greet")).thenReturn(List.of(
                new CannedResponse(UUID.randomUUID(), tenant, "Greeting", "greet", "Hello", "GENERAL", true, 2L, user)
        ));

        List<CannedResponseDto> results = service.search(tenantId, "/greet");

        assertEquals(1, results.size());
        assertEquals("greet", results.get(0).getShortcut());
    }
}
