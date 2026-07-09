package com.chatcrmlite.backend.services.lead;

import com.chatcrmlite.backend.dto.BulkLeadRowDTO;
import com.chatcrmlite.backend.dto.RowErrorDTO;
import com.chatcrmlite.backend.event.LeadCreatedEvent;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.LeadRepository;
import com.chatcrmlite.backend.services.ReferenceNumberService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Persists validated bulk lead upload rows as Contact + Lead pairs.
 *
 * Each row is processed in its own try/catch so a single row failure
 * does not roll back successfully created leads.
 *
 * Requirement 5.3 — bulk-lead-upload spec.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BulkLeadPersister {

    private final ContactRepository contactRepository;
    private final LeadRepository leadRepository;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final ReferenceNumberService referenceNumberService;

    @org.springframework.context.annotation.Lazy
    private final LeadService leadService;

    /**
     * Persist each valid row as a Contact + Lead, publishing a {@link LeadCreatedEvent}
     * for every successfully created lead.
     *
     * @param validRows pre-validated rows from the upload file
     * @param owner     the authenticated user who owns the upload
     * @param errors    mutable list to append per-row errors into
     * @return list of successfully persisted {@link Lead} objects
     */
    @Transactional
    public List<Lead> persist(List<BulkLeadRowDTO> validRows, User owner, List<RowErrorDTO> errors) {
        List<Lead> created = new ArrayList<>();

        for (BulkLeadRowDTO row : validRows) {
            try {
                // ── 1. Create Contact ──────────────────────────────────────
                String waId = (row.getPhone() != null && !row.getPhone().isBlank())
                        ? row.getPhone()
                        : "bulk-" + UUID.randomUUID().toString().substring(0, 8);

                String source = (row.getSource() != null && !row.getSource().isBlank())
                        ? row.getSource()
                        : "BULK_UPLOAD";

                Contact contact = Contact.builder()
                        .waId(waId)
                        .email(row.getEmail())
                        .name(row.getName())
                        .source(source)
                        .owner(owner)
                        .build();

                contact = contactRepository.save(contact);

                // ── 2. Create Lead ─────────────────────────────────────────
                Lead.LeadStatus status = parseStatus(row.getStatus());

                String leadNumber = referenceNumberService.generate(owner, ReferenceNumberService.EntityType.LEAD);

                Lead lead = Lead.builder()
                        .contact(contact)
                        .owner(owner)
                        .status(status)
                        .leadNumber(leadNumber)
                        .createdAt(LocalDateTime.now())
                        .lastActivity(LocalDateTime.now())
                        .build();

                Lead savedLead = leadRepository.save(lead);

                // ── 2.5 Create System Enquiry for Import ─────────────────────
                String enquiryText = (row.getNotes() != null && !row.getNotes().isBlank())
                        ? "Lead imported via Bulk Upload. Notes: " + row.getNotes()
                        : "Lead imported via Bulk Upload.";

                java.util.Map<String, String> collectedData = new java.util.HashMap<>();
                if (row.getName() != null && !row.getName().isBlank()) collectedData.put("name", row.getName());
                if (row.getEmail() != null && !row.getEmail().isBlank()) collectedData.put("email", row.getEmail());
                if (row.getPhone() != null && !row.getPhone().isBlank()) collectedData.put("phone", row.getPhone());
                if (row.getNotes() != null && !row.getNotes().isBlank()) collectedData.put("requirement", row.getNotes());
                if (row.getTags() != null && !row.getTags().isBlank()) collectedData.put("Tags", row.getTags());
                
                leadService.appendEnquiryToLead(savedLead, enquiryText, "SYSTEM", source, collectedData);

                // ── 3. Publish event ───────────────────────────────────────
                applicationEventPublisher.publishEvent(
                        new LeadCreatedEvent(this, savedLead, "BULK_UPLOAD"));

                created.add(savedLead);

            } catch (Exception e) {
                log.warn("[BulkLeadPersister] Failed to persist row {}: {}", row.getRowNumber(), e.getMessage(), e);
                errors.add(new RowErrorDTO(row.getRowNumber(), "persist failed: " + e.getMessage()));
            }
        }

        return created;
    }

    /**
     * Maps a raw status string to a {@link Lead.LeadStatus}.
     * Returns {@link Lead.LeadStatus#NEW} for blank or unrecognised values.
     */
    private Lead.LeadStatus parseStatus(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            return Lead.LeadStatus.NEW;
        }
        try {
            return Lead.LeadStatus.valueOf(rawStatus.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.debug("[BulkLeadPersister] Unrecognised lead status '{}', defaulting to NEW", rawStatus);
            return Lead.LeadStatus.NEW;
        }
    }
}
