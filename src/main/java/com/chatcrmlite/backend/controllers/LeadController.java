package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.dto.ContactDTO;
import com.chatcrmlite.backend.dto.DealUpdateDTO;
import com.chatcrmlite.backend.dto.EnquiryDTO;
import com.chatcrmlite.backend.dto.EnquiryRequest;
import com.chatcrmlite.backend.dto.LeadDTO;
import com.chatcrmlite.backend.dto.RevenueReportDTO;
import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.Tag;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.LeadMetricsService;
import com.chatcrmlite.backend.services.lead.LeadService;
import com.chatcrmlite.backend.services.lead.LeadExportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/leads")
public class LeadController {

    @Autowired private LeadService leadService;
    @Autowired private UserRepository userRepository;
    @Autowired private LeadMetricsService leadMetricsService;
    @Autowired private LeadExportService leadExportService;

    private User getAuthenticatedUser() {
        String email = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    @GetMapping("/export")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<byte[]> exportLeads(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "csv") String format) {
        User user = getAuthenticatedUser();
        List<Lead> leads = leadService.getLeadsByUserPaged(user, 0, Integer.MAX_VALUE, null).getContent();

        // Filter by Date Range (inclusive)
        LocalDateTime start = startDate != null ? startDate.atStartOfDay() : null;
        LocalDateTime end = endDate != null ? endDate.atTime(23, 59, 59, 999999999) : null;

        List<Lead> filteredLeads = leads.stream()
                .filter(lead -> {
                    if (start != null && lead.getCreatedAt().isBefore(start)) {
                        return false;
                    }
                    if (end != null && lead.getCreatedAt().isAfter(end)) {
                        return false;
                    }
                    return true;
                })
                .collect(Collectors.toList());

        byte[] fileBytes;
        String filename;
        MediaType mediaType;

        String timestamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(LocalDateTime.now());
        String businessName = user.getBusinessName();

        if ("excel".equalsIgnoreCase(format)) {
            fileBytes = leadExportService.exportToExcel(filteredLeads, businessName);
            filename = "leads_export_" + timestamp + ".xlsx";
            mediaType = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        } else {
            fileBytes = leadExportService.exportToCsv(filteredLeads, businessName);
            filename = "leads_export_" + timestamp + ".csv";
            mediaType = MediaType.parseMediaType("text/csv");
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(mediaType)
                .body(fileBytes);
    }

    // ── Lead Queries ───────────────────────────────────────────────────────

    /** GET /api/v1/leads/paged?page=0&size=20&status=NEW — paginated for large datasets */
    @GetMapping("/paged")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<?> getLeadsPaged(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Lead.LeadStatus status) {
        User user = getAuthenticatedUser();
        var pagedResult = leadService.getLeadsByUserPaged(user, page, size, status);
        return ResponseEntity.ok(java.util.Map.of(
                "content",       pagedResult.getContent().stream().map(lead -> toDTO(lead, user)).collect(Collectors.toList()),
                "totalElements", pagedResult.getTotalElements(),
                "totalPages",    pagedResult.getTotalPages(),
                "page",          pagedResult.getNumber(),
                "size",          pagedResult.getSize()
        ));
    }

    @GetMapping("/status/{status}")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<List<LeadDTO>> getLeadsByStatus(@PathVariable Lead.LeadStatus status) {
        User user = getAuthenticatedUser();
        List<Lead> leads = leadService.getLeadsByUserPaged(user, 0, Integer.MAX_VALUE, status).getContent();
        return ResponseEntity.ok(leads.stream().map(lead -> toDTO(lead, user)).collect(Collectors.toList()));
    }

    @GetMapping("/{id}")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<LeadDTO> getLead(@PathVariable UUID id) {
        User user = getAuthenticatedUser();
        return ResponseEntity.ok(toDTO(leadService.getLeadById(id, user), user));
    }

    /** GET /api/v1/leads/by-number/{leadNumber} — get lead by leadNumber */
    @GetMapping("/by-number/{leadNumber}")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<LeadDTO> getLeadByNumber(@PathVariable String leadNumber) {
        User user = getAuthenticatedUser();
        return ResponseEntity.ok(toDTO(leadService.getLeadByLeadNumber(leadNumber, user), user));
    }

    /** GET /api/v1/leads/contact/{contactId} — ALL leads for a contact */
    @GetMapping("/contact/{contactId}")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<List<LeadDTO>> getLeadsByContact(@PathVariable UUID contactId) {
        User user = getAuthenticatedUser();
        return ResponseEntity.ok(leadService.getLeadsByContactId(contactId, user)
                .stream().map(lead -> toDTO(lead, user)).collect(Collectors.toList()));
    }

    /** GET /api/v1/leads/contact/{contactId}/latest — most recent lead */
    @GetMapping("/contact/{contactId}/latest")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<LeadDTO> getLatestLeadByContact(@PathVariable UUID contactId) {
        User user = getAuthenticatedUser();
        return ResponseEntity.ok(toDTO(leadService.getLatestLeadByContactId(contactId, user), user));
    }

    @GetMapping("/revenue")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<RevenueReportDTO> getRevenueReport() {
        return ResponseEntity.ok(leadService.getRevenueReport(getAuthenticatedUser()));
    }

    // ── Status ─────────────────────────────────────────────────────────────

    @PatchMapping("/{id}/status")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<LeadDTO> updateStatus(
            @PathVariable UUID id,
            @RequestParam Lead.LeadStatus status) {
        User user = getAuthenticatedUser();
        return ResponseEntity.ok(toDTO(leadService.updateStatus(id, status, user), user));
    }

    @PostMapping("/{id}/rescore")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<LeadDTO> rescoreLead(@PathVariable UUID id) {
        User user = getAuthenticatedUser();
        return ResponseEntity.ok(toDTO(leadService.rescoreLead(id, user), user));
    }


    // ── Enquiry CRUD ───────────────────────────────────────────────────────

    @GetMapping("/{id}/enquiries")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<List<EnquiryDTO>> getEnquiries(@PathVariable UUID id) {
        return ResponseEntity.ok(leadService.getEnquiries(id, getAuthenticatedUser()));
    }

    /** GET /api/v1/leads/by-number/{leadNumber}/enquiries — list all enquiries by leadNumber */
    @GetMapping("/by-number/{leadNumber}/enquiries")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<List<EnquiryDTO>> getEnquiriesByNumber(@PathVariable String leadNumber) {
        User user = getAuthenticatedUser();
        Lead lead = leadService.getLeadByLeadNumber(leadNumber, user);
        return ResponseEntity.ok(leadService.getEnquiries(lead.getId(), user));
    }

    /** POST /api/v1/leads/{id}/enquiries — add a new enquiry */
    @PostMapping("/{id}/enquiries")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<LeadDTO> addEnquiry(
            @PathVariable UUID id,
            @RequestBody EnquiryRequest req) {
        User user = getAuthenticatedUser();
        leadService.addEnquiry(id, req, user);
        return ResponseEntity.ok(toDTO(leadService.getLeadById(id, user), user));
    }

    /** PATCH /api/v1/leads/{id}/enquiries/{enquiryId} — update an enquiry */
    @PatchMapping("/{id}/enquiries/{enquiryId}")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<LeadDTO> updateEnquiry(
            @PathVariable UUID id,
            @PathVariable String enquiryId,
            @RequestBody EnquiryRequest req) {
        User user = getAuthenticatedUser();
        leadService.updateEnquiry(id, enquiryId, req, user);
        return ResponseEntity.ok(toDTO(leadService.getLeadById(id, user), user));
    }

    /** DELETE /api/v1/leads/{id}/enquiries/{enquiryId} — delete an enquiry */
    @DeleteMapping("/{id}/enquiries/{enquiryId}")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<LeadDTO> deleteEnquiry(
            @PathVariable UUID id,
            @PathVariable String enquiryId) {
        User user = getAuthenticatedUser();
        leadService.deleteEnquiry(id, enquiryId, user);
        return ResponseEntity.ok(toDTO(leadService.getLeadById(id, user), user));
    }

    // ── Deal ───────────────────────────────────────────────────────────────

    @PatchMapping("/{id}/deal")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<LeadDTO> updateDeal(
            @PathVariable UUID id,
            @RequestBody DealUpdateDTO dto) {
        User user = getAuthenticatedUser();
        return ResponseEntity.ok(toDTO(leadService.updateDealInfo(id, dto, user), user));
    }

    // ── Metrics (11.1, 11.3, 11.5) ────────────────────────────────────────

    /** GET /api/v1/leads/metrics/distribution — leads-per-contact stats */
    @GetMapping("/metrics/distribution")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<?> getLeadsDistribution() {
        return ResponseEntity.ok(leadMetricsService.getLeadsPerContactDistribution(getAuthenticatedUser()));
    }

    /** GET /api/v1/leads/metrics/performance — API response time summary */
    @GetMapping("/metrics/performance")
    public ResponseEntity<?> getApiPerformance() {
        return ResponseEntity.ok(leadMetricsService.getApiPerformanceSummary());
    }

    // ── Mapper ─────────────────────────────────────────────────────────────

    private LeadDTO toDTO(Lead lead, User owner) {
        LocalDateTime createdAt = lead.getCreatedAt();
        boolean isNew = createdAt != null && createdAt.isAfter(LocalDateTime.now().minusHours(24));
        String createdAtHuman = formatRelativeTime(createdAt);

        // Extract latest phone and email from enquiries if available
        String latestPhone = null;
        String latestEmail = lead.getContact().getEmail();
        for (int i = lead.getEnquiryList().size() - 1; i >= 0; i--) {
            var enq = lead.getEnquiryList().get(i);
            if (enq.getPhone() != null && !enq.getPhone().isBlank()) latestPhone = enq.getPhone();
            if (enq.getEmail() != null && !enq.getEmail().isBlank()) latestEmail = enq.getEmail();
            if (latestPhone != null && latestEmail != null && !latestEmail.equals(lead.getContact().getEmail())) break;
        }

        // Fallback for leadNumber if it's null (for old leads)
        String finalLeadNumber = lead.getLeadNumber();
        if (finalLeadNumber == null || finalLeadNumber.isBlank()) {
            finalLeadNumber = "L-" + lead.getId().toString().substring(0, 6).toUpperCase();
        }

        return LeadDTO.builder()
                .id(lead.getId())
                .leadNumber(finalLeadNumber)
                .contact(ContactDTO.builder()
                        .id(lead.getContact().getId())
                        .waId(lead.getContact().getWaId())
                        .name(lead.getContact().getName())
                        .email(latestEmail)
                        .phone(latestPhone)
                        .tags(lead.getContact().getTags().stream()
                                .map(Tag::getName)
                                .collect(Collectors.toList()))
                        .source(lead.getContact().getSource())
                        .build())
                .status(lead.getStatus())
                .enquiries(lead.getEnquiryList().stream().map(e -> EnquiryDTO.builder()
                        .id(e.getId().toString())
                        .type(e.getType())
                        .message(e.getMessage())
                        .source(e.getSource())
                        .status(e.getStatus())
                        .name(e.getName())
                        .email(e.getEmail())
                        .phone(e.getPhone())
                        .company(e.getCompany())
                        .serviceCategory(e.getServiceCategory())
                        .requirement(e.getRequirement())
                        .budget(e.getBudget())
                        .city(e.getCity())
                        .country(e.getCountry())
                        .createdAt(e.getCreatedAt() != null ? e.getCreatedAt().toString() : null)
                        .build()).collect(Collectors.toList()))
                .createdAt(createdAt)
                .lastActivity(lead.getLastActivity())
                .dealValue(lead.getDealValue())
                .paymentStatus(lead.getPaymentStatus())
                .currency(lead.getCurrency())
                .dealLabel(lead.getDealLabel())
                .isNew(isNew)
                .createdAtHuman(createdAtHuman)
                .ownerName(lead.getOwner() != null ? 
                        (lead.getOwner().getDisplayName() != null && !lead.getOwner().getDisplayName().isBlank() 
                            ? lead.getOwner().getDisplayName() 
                            : lead.getOwner().getEmail()) 
                        : "Unknown")
                .score(lead.getScore())
                .build();
    }

    /**
     * Converts a LocalDateTime to a human-readable relative time string.
     * Examples: "Just now", "5 mins ago", "2 hours ago", "Yesterday", "3 days ago"
     */
    private String formatRelativeTime(LocalDateTime dateTime) {
        if (dateTime == null) return "";

        LocalDateTime now = LocalDateTime.now();
        long minutes = java.time.Duration.between(dateTime, now).toMinutes();
        long hours   = java.time.Duration.between(dateTime, now).toHours();
        long days    = java.time.Duration.between(dateTime, now).toDays();

        if (minutes < 1)   return "Just now";
        if (minutes < 60)  return minutes + " min" + (minutes == 1 ? "" : "s") + " ago";
        if (hours < 24)    return hours + " hour" + (hours == 1 ? "" : "s") + " ago";
        if (days == 1)     return "Yesterday";
        if (days < 7)      return days + " days ago";
        if (days < 30)     return (days / 7) + " week" + (days / 7 == 1 ? "" : "s") + " ago";
        if (days < 365)    return (days / 30) + " month" + (days / 30 == 1 ? "" : "s") + " ago";
        return (days / 365) + " year" + (days / 365 == 1 ? "" : "s") + " ago";
    }
}
