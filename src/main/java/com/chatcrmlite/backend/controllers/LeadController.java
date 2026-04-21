package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.dto.ContactDTO;
import com.chatcrmlite.backend.dto.DealUpdateDTO;
import com.chatcrmlite.backend.dto.EnquiryDTO;
import com.chatcrmlite.backend.dto.EnquiryRequest;
import com.chatcrmlite.backend.dto.LeadDTO;
import com.chatcrmlite.backend.dto.RevenueReportDTO;
import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.LeadMetricsService;
import com.chatcrmlite.backend.services.LeadService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/leads")
public class LeadController {

    @Autowired private LeadService leadService;
    @Autowired private UserRepository userRepository;
    @Autowired private LeadMetricsService leadMetricsService;

    private User getAuthenticatedUser() {
        String email = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    // ── Lead Queries ───────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<List<LeadDTO>> getLeads() {
        return ResponseEntity.ok(leadService.getLeadsByUser(getAuthenticatedUser())
                .stream().map(this::toDTO).collect(Collectors.toList()));
    }

    /** GET /api/v1/leads/paged?page=0&size=20 — paginated for large datasets */
    @GetMapping("/paged")
    public ResponseEntity<?> getLeadsPaged(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var pagedResult = leadService.getLeadsByUserPaged(getAuthenticatedUser(), page, size);
        return ResponseEntity.ok(java.util.Map.of(
                "content",       pagedResult.getContent().stream().map(this::toDTO).collect(Collectors.toList()),
                "totalElements", pagedResult.getTotalElements(),
                "totalPages",    pagedResult.getTotalPages(),
                "page",          pagedResult.getNumber(),
                "size",          pagedResult.getSize()
        ));
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<List<LeadDTO>> getLeadsByStatus(@PathVariable Lead.LeadStatus status) {
        return ResponseEntity.ok(leadService.getLeadsByStatus(status, getAuthenticatedUser())
                .stream().map(this::toDTO).collect(Collectors.toList()));
    }

    /** GET /api/v1/leads/contact/{contactId} — ALL leads for a contact */
    @GetMapping("/contact/{contactId}")
    public ResponseEntity<List<LeadDTO>> getLeadsByContact(@PathVariable UUID contactId) {
        return ResponseEntity.ok(leadService.getLeadsByContactId(contactId, getAuthenticatedUser())
                .stream().map(this::toDTO).collect(Collectors.toList()));
    }

    /** GET /api/v1/leads/contact/{contactId}/latest — most recent lead */
    @GetMapping("/contact/{contactId}/latest")
    public ResponseEntity<LeadDTO> getLatestLeadByContact(@PathVariable UUID contactId) {
        return ResponseEntity.ok(toDTO(leadService.getLatestLeadByContactId(contactId, getAuthenticatedUser())));
    }

    @GetMapping("/revenue")
    public ResponseEntity<RevenueReportDTO> getRevenueReport() {
        return ResponseEntity.ok(leadService.getRevenueReport(getAuthenticatedUser()));
    }

    // ── Status ─────────────────────────────────────────────────────────────

    @PatchMapping("/{id}/status")
    public ResponseEntity<LeadDTO> updateStatus(
            @PathVariable UUID id,
            @RequestParam Lead.LeadStatus status) {
        return ResponseEntity.ok(toDTO(leadService.updateStatus(id, status, getAuthenticatedUser())));
    }

    // ── Enquiry CRUD ───────────────────────────────────────────────────────

    /** GET /api/v1/leads/{id}/enquiries — list all enquiries */
    @GetMapping("/{id}/enquiries")
    public ResponseEntity<List<EnquiryDTO>> getEnquiries(@PathVariable UUID id) {
        return ResponseEntity.ok(leadService.getEnquiries(id, getAuthenticatedUser()));
    }

    /** POST /api/v1/leads/{id}/enquiries — add a new enquiry */
    @PostMapping("/{id}/enquiries")
    public ResponseEntity<LeadDTO> addEnquiry(
            @PathVariable UUID id,
            @RequestBody EnquiryRequest req) {
        return ResponseEntity.ok(toDTO(leadService.addEnquiry(id, req, getAuthenticatedUser())));
    }

    /** PATCH /api/v1/leads/{id}/enquiries/{enquiryId} — update an enquiry */
    @PatchMapping("/{id}/enquiries/{enquiryId}")
    public ResponseEntity<LeadDTO> updateEnquiry(
            @PathVariable UUID id,
            @PathVariable String enquiryId,
            @RequestBody EnquiryRequest req) {
        return ResponseEntity.ok(toDTO(leadService.updateEnquiry(id, enquiryId, req, getAuthenticatedUser())));
    }

    /** DELETE /api/v1/leads/{id}/enquiries/{enquiryId} — delete an enquiry */
    @DeleteMapping("/{id}/enquiries/{enquiryId}")
    public ResponseEntity<LeadDTO> deleteEnquiry(
            @PathVariable UUID id,
            @PathVariable String enquiryId) {
        return ResponseEntity.ok(toDTO(leadService.deleteEnquiry(id, enquiryId, getAuthenticatedUser())));
    }

    // ── Deal ───────────────────────────────────────────────────────────────

    @PatchMapping("/{id}/deal")
    public ResponseEntity<LeadDTO> updateDeal(
            @PathVariable UUID id,
            @RequestBody DealUpdateDTO dto) {
        return ResponseEntity.ok(toDTO(leadService.updateDealInfo(id, dto, getAuthenticatedUser())));
    }

    // ── Metrics (11.1, 11.3, 11.5) ────────────────────────────────────────

    /** GET /api/v1/leads/metrics/distribution — leads-per-contact stats */
    @GetMapping("/metrics/distribution")
    public ResponseEntity<?> getLeadsDistribution() {
        return ResponseEntity.ok(leadMetricsService.getLeadsPerContactDistribution());
    }

    /** GET /api/v1/leads/metrics/performance — API response time summary */
    @GetMapping("/metrics/performance")
    public ResponseEntity<?> getApiPerformance() {
        return ResponseEntity.ok(leadMetricsService.getApiPerformanceSummary());
    }

    // ── Mapper ─────────────────────────────────────────────────────────────

    private LeadDTO toDTO(Lead lead) {
        return LeadDTO.builder()
                .id(lead.getId())
                .contact(ContactDTO.builder()
                        .id(lead.getContact().getId())
                        .waId(lead.getContact().getWaId())
                        .name(lead.getContact().getName())
                        .tags(lead.getContact().getTags())
                        .source(lead.getContact().getSource())
                        .build())
                .status(lead.getStatus())
                .enquiries(leadService.parseEnquiries(lead.getEnquiries()))
                .createdAt(lead.getCreatedAt())
                .lastActivity(lead.getLastActivity())
                .dealValue(lead.getDealValue())
                .paymentStatus(lead.getPaymentStatus())
                .currency(lead.getCurrency())
                .dealLabel(lead.getDealLabel())
                .build();
    }
}
