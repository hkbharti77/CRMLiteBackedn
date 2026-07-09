package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.dto.BulkUploadResultDTO;
import com.chatcrmlite.backend.dto.RowErrorDTO;
import com.chatcrmlite.backend.dto.ValidationConfigDTO;
import com.chatcrmlite.backend.models.BulkUploadValidationConfig;
import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.BulkUploadValidationConfigRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.lead.BulkLeadNotifier;
import com.chatcrmlite.backend.services.lead.BulkLeadParser;
import com.chatcrmlite.backend.services.lead.BulkLeadPersister;
import com.chatcrmlite.backend.services.lead.BulkLeadTemplateService;
import com.chatcrmlite.backend.services.lead.BulkLeadValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for {@link LeadBulkUploadController}.
 *
 * <p>Uses {@code @WebMvcTest} with mocked service dependencies to cover
 * the HTTP layer: routing, request parsing, status codes, and response bodies.
 */
@WebMvcTest(LeadBulkUploadController.class)
class LeadBulkUploadControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean BulkLeadParser parser;
    @MockBean BulkLeadValidator validator;
    @MockBean BulkLeadPersister persister;
    @MockBean BulkLeadNotifier notifier;
    @MockBean BulkLeadTemplateService templateService;
    @MockBean BulkUploadValidationConfigRepository validationConfigRepository;
    @MockBean UserRepository userRepository;

    private User testUser;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        Tenant tenant = new Tenant();
        tenant.setId(tenantId);

        testUser = new User();
        testUser.setId(UUID.randomUUID());
        testUser.setEmail("test@example.com");
        testUser.setRole(User.Role.OWNER);
        testUser.setTenant(tenant);

        // Mock security context
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn("test@example.com");
        SecurityContext ctx = mock(SecurityContext.class);
        when(ctx.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(ctx);

        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
    }

    // ── POST /api/v1/leads/bulk-upload ───────────────────────────────────────

    @Test
    void uploadLeads_success_returns200WithResult() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "leads.csv", "text/csv", "name,email\nJohn,john@test.com".getBytes());

        com.chatcrmlite.backend.dto.BulkLeadRowDTO row =
                new com.chatcrmlite.backend.dto.BulkLeadRowDTO();
        row.setRowNumber(1);
        row.setName("John");
        row.setEmail("john@test.com");

        BulkLeadValidator.ValidationResult validationResult =
                new BulkLeadValidator.ValidationResult(List.of(row), List.of());
        Lead lead = new Lead();

        when(parser.parse(any())).thenReturn(List.of(row));
        when(validationConfigRepository.findByTenantId(tenantId)).thenReturn(Optional.empty());
        when(validator.validate(any(), any(), eq(tenantId))).thenReturn(validationResult);
        when(persister.persist(any(), any(), any())).thenReturn(List.of(lead));

        mockMvc.perform(multipart("/api/v1/leads/bulk-upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRows").value(1))
                .andExpect(jsonPath("$.importedCount").value(1));
    }

    @Test
    void uploadLeads_parserThrows413_propagates() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "big.csv", "text/csv", new byte[1]);

        when(parser.parse(any())).thenThrow(
                new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "File too large (max 5 MB)"));

        mockMvc.perform(multipart("/api/v1/leads/bulk-upload").file(file))
                .andExpect(status().isPayloadTooLarge());
    }

    // ── GET /api/v1/leads/bulk-upload/template ───────────────────────────────

    @Test
    void downloadTemplate_xlsx_returnsCorrectContentType() throws Exception {
        when(templateService.generateXlsx()).thenReturn(new byte[]{1, 2, 3});

        mockMvc.perform(get("/api/v1/leads/bulk-upload/template").param("format", "xlsx"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"lead-template.xlsx\""))
                .andExpect(content().contentTypeCompatibleWith(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    }

    @Test
    void downloadTemplate_csv_returnsCorrectContentType() throws Exception {
        when(templateService.generateCsv()).thenReturn("name,email\nJohn,john@example.com");

        mockMvc.perform(get("/api/v1/leads/bulk-upload/template").param("format", "csv"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"lead-template.csv\""))
                .andExpect(content().contentTypeCompatibleWith("text/csv"));
    }

    @Test
    void downloadTemplate_unknownFormat_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/leads/bulk-upload/template").param("format", "pdf"))
                .andExpect(status().isBadRequest());
    }

    // ── PUT /api/v1/leads/bulk-upload/validation-config ──────────────────────

    @Test
    void updateValidationConfig_asOwner_returns200() throws Exception {
        ValidationConfigDTO dto = ValidationConfigDTO.builder()
                .extraRequiredFields(List.of("source", "status"))
                .build();

        when(validationConfigRepository.findByTenantId(tenantId)).thenReturn(Optional.empty());
        when(validationConfigRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(put("/api/v1/leads/bulk-upload/validation-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.extraRequiredFields[0]").value("source"));
    }

    @Test
    void updateValidationConfig_asNonAdmin_returns403() throws Exception {
        testUser.setRole(User.Role.AGENT);

        ValidationConfigDTO dto = ValidationConfigDTO.builder()
                .extraRequiredFields(List.of("source"))
                .build();

        mockMvc.perform(put("/api/v1/leads/bulk-upload/validation-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isForbidden());
    }
}
