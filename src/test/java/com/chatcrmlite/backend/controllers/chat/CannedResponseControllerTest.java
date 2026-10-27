package com.chatcrmlite.backend.controllers.chat;

import com.chatcrmlite.backend.dto.chat.CannedResponseDto;
import com.chatcrmlite.backend.dto.chat.CreateCannedResponseRequest;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.chat.CannedResponseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CannedResponseControllerTest {

    @Mock
    private CannedResponseService service;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ContactRepository contactRepository;

    @InjectMocks
    private CannedResponseController controller;

    private User user;
    private Tenant tenant;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        tenant = new Tenant();
        tenant.setId(tenantId);
        tenant.setBusinessName("Test Tenant");

        user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("agent@tenant.com");
        user.setDisplayName("Test Agent");
        user.setTenant(tenant);

        lenient().when(userRepository.findByEmail("agent@tenant.com")).thenReturn(Optional.of(user));
    }

    @Test
    @DisplayName("GET /api/v1/canned-responses - returns list")
    void testGetAll() {
        CannedResponseDto dto = CannedResponseDto.builder()
                .id(UUID.randomUUID())
                .title("Greeting")
                .shortcut("greet")
                .content("Hello")
                .category("GREETINGS")
                .build();

        when(service.getAll(tenantId)).thenReturn(List.of(dto));

        ResponseEntity<List<CannedResponseDto>> response = controller.getAll(null, "agent@tenant.com");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
        assertEquals("greet", response.getBody().get(0).getShortcut());
    }

    @Test
    @DisplayName("GET /api/v1/canned-responses/search?q=query")
    void testSearch() {
        CannedResponseDto dto = CannedResponseDto.builder()
                .id(UUID.randomUUID())
                .title("Support")
                .shortcut("help")
                .content("How can I help?")
                .build();

        when(service.search(tenantId, "help")).thenReturn(List.of(dto));

        ResponseEntity<List<CannedResponseDto>> response = controller.search("help", "agent@tenant.com");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
        assertEquals("help", response.getBody().get(0).getShortcut());
    }

    @Test
    @DisplayName("POST /api/v1/canned-responses - creates new response")
    void testCreate() {
        CreateCannedResponseRequest req = CreateCannedResponseRequest.builder()
                .title("New Snippet")
                .shortcut("new")
                .content("New Content")
                .category("GENERAL")
                .build();

        CannedResponseDto dto = CannedResponseDto.builder()
                .id(UUID.randomUUID())
                .title("New Snippet")
                .shortcut("new")
                .content("New Content")
                .build();

        when(service.create(eq(req), eq(user))).thenReturn(dto);

        ResponseEntity<?> response = controller.create(req, "agent@tenant.com");

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals(dto, response.getBody());
    }

    @Test
    @DisplayName("DELETE /api/v1/canned-responses/{id} - deletes response")
    void testDelete() {
        UUID id = UUID.randomUUID();
        doNothing().when(service).delete(id, user);

        ResponseEntity<?> response = controller.delete(id, "agent@tenant.com");

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(service).delete(id, user);
    }

    @Test
    @DisplayName("POST /api/v1/canned-responses/{id}/use - records usage")
    void testRecordUsage() {
        UUID id = UUID.randomUUID();
        doNothing().when(service).recordUsage(id, tenantId);

        ResponseEntity<Void> response = controller.recordUsage(id, "agent@tenant.com");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(service).recordUsage(id, tenantId);
    }

    @Test
    @DisplayName("POST /api/v1/canned-responses/interpolate - resolves template variables")
    void testInterpolate() {
        when(service.interpolatePlaceholders(anyString(), any(), any())).thenReturn("Resolved Hello");

        ResponseEntity<Map<String, String>> response = controller.interpolate(
                null,
                Map.of("content", "Hello {{contact.name}}"),
                "agent@tenant.com"
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("Resolved Hello", response.getBody().get("interpolated"));
    }
}
