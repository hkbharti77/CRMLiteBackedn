package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.dto.DealUpdateDTO;
import com.chatcrmlite.backend.dto.EnquiryDTO;
import com.chatcrmlite.backend.dto.EnquiryRequest;
import com.chatcrmlite.backend.dto.RevenueReportDTO;
import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.LeadRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class LeadService {

    @Autowired
    private LeadRepository leadRepository;

    @Autowired
    private com.chatcrmlite.backend.repositories.ContactRepository contactRepository;

    @Autowired
    private ObjectMapper objectMapper;

    // ── Helpers ────────────────────────────────────────────────────────────

    public List<EnquiryDTO> parseEnquiries(String json) {
        try {
            if (json == null || json.isBlank()) return new ArrayList<>();
            return objectMapper.readValue(json, new TypeReference<List<EnquiryDTO>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private String serializeEnquiries(List<EnquiryDTO> list) {
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            return "[]";
        }
    }

    private Lead findOwnedLead(UUID leadId, User owner) {
        return leadRepository.findById(leadId)
                .filter(l -> l.getOwner().getId().equals(owner.getId()))
                .orElseThrow(() -> new RuntimeException("Lead not found"));
    }

    // ── Lead Queries ───────────────────────────────────────────────────────

    /** Validate lead creation scenarios and business rules */
    public void validateLeadCreation(com.chatcrmlite.backend.models.Contact contact, User owner, String enquiryType) {
        if (contact == null) {
            throw new IllegalArgumentException("Contact cannot be null for lead creation");
        }
        if (owner == null) {
            throw new IllegalArgumentException("Owner cannot be null for lead creation");
        }
        if (enquiryType == null || enquiryType.trim().isEmpty()) {
            throw new IllegalArgumentException("Enquiry type cannot be null or empty");
        }
        
        // Validate that contact belongs to the owner
        if (!contact.getOwner().getId().equals(owner.getId())) {
            throw new IllegalArgumentException("Contact does not belong to the specified owner");
        }
        
        // Validate enquiry type
        if (!"NEW_ENQUIRY".equals(enquiryType) && !"ONGOING".equals(enquiryType)) {
            throw new IllegalArgumentException("Invalid enquiry type: " + enquiryType);
        }
        
        // Business rule: Check if contact has too many active leads (prevent spam)
        long activeLeadCount = getActiveLeadCountByContactId(contact.getId(), owner);
        if (activeLeadCount >= 10) { // Configurable limit
            throw new IllegalStateException("Contact has too many active leads (" + activeLeadCount + "). Maximum allowed: 10");
        }
    }

    /** Get all leads for a contact (multiple leads per contact) */
    @Cacheable(value = "leadsByContact", key = "#contactId + '_' + #owner.id")
    public List<Lead> getLeadsByContactId(UUID contactId, User owner) {
        com.chatcrmlite.backend.models.Contact contact = contactRepository.findById(contactId)
                .orElseThrow(() -> new RuntimeException("Contact not found"));
        return leadRepository.findAllByContact(contact).stream()
                .filter(l -> l.getOwner().getId().equals(owner.getId()))
                .collect(java.util.stream.Collectors.toList());
    }

    /** Get the most recent lead for a contact — used by ContactProfile */
    @Cacheable(value = "latestLeadByContact", key = "#contactId + '_' + #owner.id")
    public Lead getLatestLeadByContactId(UUID contactId, User owner) {
        com.chatcrmlite.backend.models.Contact contact = contactRepository.findById(contactId)
                .orElseThrow(() -> new RuntimeException("Contact not found"));
        return leadRepository.findAllByContact(contact).stream()
                .filter(l -> l.getOwner().getId().equals(owner.getId()))
                .max(java.util.Comparator.comparing(Lead::getCreatedAt))
                .orElseThrow(() -> new RuntimeException("No lead found for this contact"));
    }

    /** Get count of active (non-closed) leads for a contact */
    @Cacheable(value = "activeLeadCount", key = "#contactId + '_' + #owner.id")
    public long getActiveLeadCountByContactId(UUID contactId, User owner) {
        com.chatcrmlite.backend.models.Contact contact = contactRepository.findById(contactId)
                .orElseThrow(() -> new RuntimeException("Contact not found"));
        
        List<Lead.LeadStatus> closedStatuses = List.of(
                Lead.LeadStatus.CLOSED_WON, Lead.LeadStatus.CLOSED_LOST);
        
        return leadRepository.findAllByContact(contact).stream()
                .filter(l -> l.getOwner().getId().equals(owner.getId()))
                .filter(l -> !closedStatuses.contains(l.getStatus()))
                .count();
    }

    /** Get all leads for a user — paginated for contacts with many leads */
    public Page<Lead> getLeadsByUserPaged(User user, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "lastActivity"));
        return leadRepository.findAllByOwnerPaged(user, pageable);
    }

    @Cacheable(value = "leadsByUser", key = "#user.id")
    public List<Lead> getLeadsByUser(User user) {
        // Use optimized JOIN FETCH to avoid N+1 on contact
        return leadRepository.findAllByOwnerWithContact(user);
    }

    @Cacheable(value = "leadsByStatus", key = "#status + '_' + #user.id")
    public List<Lead> getLeadsByStatus(Lead.LeadStatus status, User user) {
        return leadRepository.findAllByStatusAndOwner(status, user);
    }

    // ── Status Update ──────────────────────────────────────────────────────

    @CacheEvict(value = {"leadsByContact", "latestLeadByContact", "activeLeadCount", "leadsByUser", "leadsByStatus"}, allEntries = true)
    public Lead updateStatus(UUID leadId, Lead.LeadStatus status, User owner) {
        Lead lead = findOwnedLead(leadId, owner);
        Lead.LeadStatus oldStatus = lead.getStatus();
        
        // Log status transition with context
        log.info("[Lead-Status] Updating lead {} from {} to {} for contact {} (owner: {})", 
                leadId, oldStatus, status, 
                lead.getContact().getWaId(), owner.getId());
        
        lead.setStatus(status);
        lead.setLastActivity(LocalDateTime.now());
        Lead savedLead = leadRepository.save(lead);
        
        // Log successful transition
        log.info("[Lead-Status] Successfully updated lead {} status from {} to {} for contact {}", 
                leadId, oldStatus, status, lead.getContact().getWaId());
        
        // Log business-critical transitions
        if (status == Lead.LeadStatus.CLOSED_WON) {
            log.info("[Lead-Revenue] Lead {} closed as WON with deal value: {} {} for contact {}", 
                    leadId, lead.getDealValue(), lead.getCurrency(), lead.getContact().getWaId());
        } else if (status == Lead.LeadStatus.CLOSED_LOST) {
            log.info("[Lead-Lost] Lead {} closed as LOST for contact {}", 
                    leadId, lead.getContact().getWaId());
        }
        
        return savedLead;
    }

    // ── Enquiry CRUD ───────────────────────────────────────────────────────

    /**
     * Add a new enquiry to the lead's enquiries JSON array.
     */
    @CacheEvict(value = {"leadsByContact", "latestLeadByContact"}, allEntries = true)
    public Lead addEnquiry(UUID leadId, EnquiryRequest req, User owner) {
        Lead lead = findOwnedLead(leadId, owner);
        List<EnquiryDTO> list = parseEnquiries(lead.getEnquiries());

        EnquiryDTO entry = EnquiryDTO.builder()
                .id(UUID.randomUUID().toString())
                .type(req.getType() != null ? req.getType() : "MANUAL")
                .message(req.getMessage())
                .source(req.getSource() != null ? req.getSource() : "Manual Entry")
                .status(req.getStatus() != null ? req.getStatus() : "OPEN")
                .createdAt(LocalDateTime.now().toString())
                .build();

        list.add(entry);
        lead.setEnquiries(serializeEnquiries(list));
        lead.setLastActivity(LocalDateTime.now());
        return leadRepository.save(lead);
    }

    /**
     * Update an existing enquiry by its id.
     */
    @CacheEvict(value = {"leadsByContact", "latestLeadByContact"}, allEntries = true)
    public Lead updateEnquiry(UUID leadId, String enquiryId, EnquiryRequest req, User owner) {
        Lead lead = findOwnedLead(leadId, owner);
        List<EnquiryDTO> list = parseEnquiries(lead.getEnquiries());

        EnquiryDTO target = list.stream()
                .filter(e -> enquiryId.equals(e.getId()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Enquiry not found: " + enquiryId));

        if (req.getMessage() != null) target.setMessage(req.getMessage());
        if (req.getType()    != null) target.setType(req.getType());
        if (req.getSource()  != null) target.setSource(req.getSource());
        if (req.getStatus()  != null) target.setStatus(req.getStatus());

        lead.setEnquiries(serializeEnquiries(list));
        lead.setLastActivity(LocalDateTime.now());
        return leadRepository.save(lead);
    }

    /**
     * Delete a single enquiry by its id.
     */
    @CacheEvict(value = {"leadsByContact", "latestLeadByContact"}, allEntries = true)
    public Lead deleteEnquiry(UUID leadId, String enquiryId, User owner) {
        Lead lead = findOwnedLead(leadId, owner);
        List<EnquiryDTO> list = parseEnquiries(lead.getEnquiries());

        boolean removed = list.removeIf(e -> enquiryId.equals(e.getId()));
        if (!removed) throw new RuntimeException("Enquiry not found: " + enquiryId);

        lead.setEnquiries(serializeEnquiries(list));
        lead.setLastActivity(LocalDateTime.now());
        return leadRepository.save(lead);
    }

    /**
     * Get all enquiries for a lead.
     */
    public List<EnquiryDTO> getEnquiries(UUID leadId, User owner) {
        Lead lead = findOwnedLead(leadId, owner);
        return parseEnquiries(lead.getEnquiries());
    }

    // ── Deal Update ────────────────────────────────────────────────────────

    @CacheEvict(value = {"leadsByContact", "latestLeadByContact", "leadsByUser", "revenueReport"}, allEntries = true)
    public Lead updateDealInfo(UUID leadId, DealUpdateDTO dto, User owner) {
        Lead lead = findOwnedLead(leadId, owner);
        if (dto.getDealValue()     != null) lead.setDealValue(dto.getDealValue());
        if (dto.getDealLabel()     != null) lead.setDealLabel(dto.getDealLabel());
        if (dto.getCurrency()      != null) lead.setCurrency(dto.getCurrency());
        if (dto.getPaymentStatus() != null) {
            lead.setPaymentStatus(Lead.PaymentStatus.valueOf(dto.getPaymentStatus()));
        }
        lead.setLastActivity(LocalDateTime.now());
        return leadRepository.save(lead);
    }

    // ── Revenue Report ─────────────────────────────────────────────────────

    @Cacheable(value = "revenueReport", key = "#owner.id")
    public RevenueReportDTO getRevenueReport(User owner) {
        List<Lead> leads = leadRepository.findAllByOwner(owner);

        BigDecimal totalPipeline = BigDecimal.ZERO;
        BigDecimal received      = BigDecimal.ZERO;
        BigDecimal pending       = BigDecimal.ZERO;
        long totalDeals   = 0;
        long paidDeals    = 0;
        long pendingDeals = 0;

        for (Lead lead : leads) {
            if (lead.getDealValue() == null) continue;
            BigDecimal val = lead.getDealValue();
            totalPipeline = totalPipeline.add(val);
            totalDeals++;

            Lead.PaymentStatus ps = lead.getPaymentStatus();
            if (ps == Lead.PaymentStatus.PAID) {
                received = received.add(val);
                paidDeals++;
            } else if (ps == Lead.PaymentStatus.PENDING || ps == Lead.PaymentStatus.PARTIAL) {
                pending = pending.add(val);
                pendingDeals++;
            }
        }

        return RevenueReportDTO.builder()
                .totalPipelineValue(totalPipeline)
                .receivedRevenue(received)
                .pendingRevenue(pending)
                .totalDeals(totalDeals)
                .paidDeals(paidDeals)
                .pendingDeals(pendingDeals)
                .currency("INR")
                .build();
    }

    /**
     * Called from WhatsAppService to append an enquiry directly (no auth context needed).
     */
    public void appendEnquiryToLead(Lead lead, String message, String type, String source) {
        List<EnquiryDTO> list = parseEnquiries(lead.getEnquiries());
        EnquiryDTO entry = EnquiryDTO.builder()
                .id(UUID.randomUUID().toString())
                .type(type)
                .message(message)
                .source(source)
                .status("OPEN")
                .createdAt(LocalDateTime.now().toString())
                .build();
        list.add(entry);
        lead.setEnquiries(serializeEnquiries(list));
        lead.setLastActivity(LocalDateTime.now());
        leadRepository.save(lead);
    }
}
