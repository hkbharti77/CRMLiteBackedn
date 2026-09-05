package com.chatcrmlite.backend.services.lead;

import com.chatcrmlite.backend.dto.DealUpdateDTO;
import com.chatcrmlite.backend.dto.EnquiryDTO;
import com.chatcrmlite.backend.dto.EnquiryRequest;
import com.chatcrmlite.backend.dto.RevenueReportDTO;
import com.chatcrmlite.backend.event.LeadStatusChangedEvent;
import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.LeadAssignment;
import com.chatcrmlite.backend.models.AssignmentSource;
import com.chatcrmlite.backend.repositories.LeadAssignmentRepository;
import com.chatcrmlite.backend.repositories.LeadRepository;
import com.chatcrmlite.backend.repositories.ContactRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LeadServiceImpl implements LeadService {

    private final LeadRepository leadRepository;
    private final ContactRepository contactRepository;
    private final LeadAssignmentRepository leadAssignmentRepository;
    private final LeadValidator leadValidator;
    private final LeadEnquiryService leadEnquiryService;
    private final ApplicationEventPublisher eventPublisher;
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired
    private LeadScoringService leadScoringService;

    @org.springframework.beans.factory.annotation.Autowired
    private com.chatcrmlite.backend.services.EmailService emailService;
    
    @org.springframework.beans.factory.annotation.Autowired
    private com.chatcrmlite.backend.services.whatsapp.WhatsAppOutboundService whatsAppOutboundService;
    
    @org.springframework.beans.factory.annotation.Autowired
    private com.chatcrmlite.backend.repositories.WhatsAppConfigRepository whatsappConfigRepository;

    private boolean isAdmin(User user) {
        return user.getRole() == User.Role.ADMIN || user.getRole() == User.Role.OWNER || user.getRole() == User.Role.AGENT;
    }

    @Override
    public Lead getLeadById(UUID leadId, User owner) {
        Lead lead = findOwnedLead(leadId, owner);
        initializeLead(lead);
        return lead;
    }

    private Lead findOwnedLead(UUID leadId, User owner) {
        Lead lead = leadRepository.findByIdWithOwnerAndTenant(leadId)
                .orElseThrow(() -> new RuntimeException("Lead not found"));
        if (lead.getOwner() == null || lead.getOwner().getTenant() == null || owner == null || owner.getTenant() == null || !lead.getOwner().getTenant().getId().equals(owner.getTenant().getId())) {
            throw new RuntimeException("Lead not found");
        }
        return lead;
    }

    @Override
    @Transactional(readOnly = true)
    public Lead getLeadByLeadNumber(String leadNumber, User owner) {
        Lead lead = leadRepository.findByLeadNumber(leadNumber)
                .orElseThrow(() -> new RuntimeException("Lead not found with number: " + leadNumber));
        if (lead.getOwner() == null || lead.getOwner().getTenant() == null || owner == null || owner.getTenant() == null || !lead.getOwner().getTenant().getId().equals(owner.getTenant().getId())) {
            throw new RuntimeException("Lead not found");
        }
        initializeLead(lead);
        return lead;
    }

    public static void initializeLead(Lead lead) {
        if (lead == null) return;
        if (lead.getContact() != null) {
            lead.getContact().getId();
            lead.getContact().getWaId();
            lead.getContact().getName();
            lead.getContact().getSource();
            if (lead.getContact().getTags() != null) {
                lead.getContact().getTags().size();
            }
        }
        if (lead.getOwner() != null) {
            lead.getOwner().getId();
            lead.getOwner().getDisplayName();
            lead.getOwner().getEmail();
            if (lead.getOwner().getTenant() != null) {
                lead.getOwner().getTenant().getId();
            }
        }
        if (lead.getEnquiryList() != null) {
            lead.getEnquiryList().size();
            lead.getEnquiryList().forEach(e -> {
                e.getId();
                e.getType();
                e.getMessage();
                e.getSource();
                e.getStatus();
                e.getCreatedAt();
            });
        }
    }

    public static List<Lead> initializeLeads(List<Lead> leads) {
        if (leads != null) {
            leads.forEach(LeadServiceImpl::initializeLead);
        }
        return leads;
    }

    public static Page<Lead> initializeLeads(Page<Lead> leads) {
        if (leads != null) {
            leads.forEach(LeadServiceImpl::initializeLead);
        }
        return leads;
    }


    @Override
    public void validateLeadCreation(Contact contact, User owner, String enquiryType) {
        leadValidator.validateLeadCreation(contact, owner, enquiryType);
    }

    @Override
    @Cacheable(value = "leadsByContact", key = "#contactId + '_' + #owner.id")
    public List<Lead> getLeadsByContactId(UUID contactId, User owner) {
        Contact contact = contactRepository.findById(contactId)
                .orElseThrow(() -> new RuntimeException("Contact not found"));
        List<Lead> leads;
        if (isAdmin(owner)) {
            leads = leadRepository.findAllByContact(contact);
        } else {
            leads = leadRepository.findAllByContactAndOwnerOptimized(contact, owner);
        }
        // Initialize lazy relationships to avoid LazyInitializationException outside transaction
        initializeLeads(leads);
        return leads;
    }

    @Override
    @Cacheable(value = "latestLeadByContact", key = "#contactId + '_' + #owner.id")
    public Lead getLatestLeadByContactId(UUID contactId, User owner) {
        Contact contact = contactRepository.findById(contactId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Contact not found"));
        Lead lead = leadRepository.findAllByContact(contact).stream()
                .filter(l -> isAdmin(owner) || l.getOwner().getId().equals(owner.getId()))
                .max(java.util.Comparator.comparing(Lead::getCreatedAt))
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "No lead found for this contact"));
        // Initialize lazy relationships to avoid LazyInitializationException outside transaction
        initializeLead(lead);
        return lead;
    }

    @Override
    @Cacheable(value = "activeLeadCount", key = "#contactId + '_' + #owner.id")
    public long getActiveLeadCountByContactId(UUID contactId, User owner) {
        Contact contact = contactRepository.findById(contactId)
                .orElseThrow(() -> new RuntimeException("Contact not found"));
        
        List<Lead.LeadStatus> excludedStatuses = List.of(
                Lead.LeadStatus.BOOKED,
                Lead.LeadStatus.CLOSED_WON,
                Lead.LeadStatus.CLOSED_LOST);
        
        if (isAdmin(owner)) {
            return leadRepository.findAllByContact(contact).stream()
                    .filter(l -> !excludedStatuses.contains(l.getStatus()))
                    .count();
        } else {
            return leadRepository.countByContactAndOwnerAndStatusNotIn(contact, owner, excludedStatuses);
        }
    }

    @Override
    public long getTotalLeadCount(User owner) {
        if (isAdmin(owner)) {
            return leadRepository.count();
        }
        return leadRepository.countByOwner(owner);
    }

    @Override
    public long getLeadCountByStatus(Lead.LeadStatus status, User owner) {
        if (isAdmin(owner)) {
            return leadRepository.countByStatus(status);
        }
        return leadRepository.countByStatusAndOwner(status, owner);
    }

    @Override
    public Page<Lead> getLeadsByUserPaged(User user, int page, int size, Lead.LeadStatus status, String search) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "lastActivity"));
        Page<UUID> idPage;
        boolean hasSearch = search != null && !search.trim().isEmpty();
        
        // Step 1: Query IDs without JOIN FETCH (avoids HHH90003004 memory pagination)
        if (isAdmin(user)) {
            if (status != null) {
                idPage = hasSearch ? leadRepository.findIdsByStatusAndSearchPaged(status, search, pageable) 
                                   : leadRepository.findIdsByStatusPaged(status, pageable);
            } else {
                idPage = hasSearch ? leadRepository.findIdsAndSearchPaged(search, pageable)
                                   : leadRepository.findIdsPaged(pageable);
            }
        } else {
            if (status != null) {
                idPage = hasSearch ? leadRepository.findIdsByStatusAndOwnerAndSearchPaged(status, user, search, pageable)
                                   : leadRepository.findIdsByStatusAndOwnerPaged(status, user, pageable);
            } else {
                idPage = hasSearch ? leadRepository.findIdsByOwnerAndSearchPaged(user, search, pageable)
                                   : leadRepository.findIdsByOwnerPaged(user, pageable);
            }
        }

        if (idPage.isEmpty()) {
            return org.springframework.data.domain.Page.empty(pageable);
        }

        // Step 2: Fetch full entities with JOIN FETCH
        List<UUID> ids = idPage.getContent();
        List<Lead> unsortedLeads = leadRepository.findAllWithContactAndTagsByIdIn(ids);

        // Step 3: Re-order leads to match the original ID page order (which is sorted by lastActivity DESC)
        java.util.Map<UUID, Lead> leadMap = unsortedLeads.stream().collect(java.util.stream.Collectors.toMap(Lead::getId, l -> l));
        List<Lead> sortedLeads = ids.stream()
                .map(leadMap::get)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toList());

        // Step 4: Construct the final Page object
        Page<Lead> result = new org.springframework.data.domain.PageImpl<>(sortedLeads, pageable, idPage.getTotalElements());
        
        initializeLeads(result);
        return result;
    }

    @Override
    @CacheEvict(value = {"leadsByContact", "latestLeadByContact", "activeLeadCount", "leadsByUser", "leadsByStatus"}, allEntries = true)
    @Transactional
    public Lead updateStatus(UUID leadId, Lead.LeadStatus status, java.math.BigDecimal dealValue, String lostReason, Lead.PaymentStatus paymentStatus, Boolean sendPaymentLink, String paymentMethod, String paymentLinkUrl, User owner) {
        Lead lead = findOwnedLead(leadId, owner);
        Lead.LeadStatus oldStatus = lead.getStatus();
        
        log.info("[Lead-Status] Updating lead {} from {} to {} for contact {} (owner: {})", 
                leadId, oldStatus, status, 
                lead.getContact().getWaId(), owner.getId());
        
        lead.setStatus(status);
        if (status == Lead.LeadStatus.WON || status == Lead.LeadStatus.CLOSED_WON) {
            if (dealValue != null) lead.setDealValue(dealValue);
            if (paymentStatus != null) {
                lead.setPaymentStatus(paymentStatus);
            } else {
                lead.setPaymentStatus(Lead.PaymentStatus.PAID);
            }
        } else if (status == Lead.LeadStatus.LOST || status == Lead.LeadStatus.CLOSED_LOST) {
            if (lostReason != null) lead.setLostReason(lostReason);
        }

        lead.setLastActivity(LocalDateTime.now());
        Lead savedLead = leadRepository.save(lead);
        initializeLead(savedLead);
        
        eventPublisher.publishEvent(new LeadStatusChangedEvent(this, savedLead, oldStatus, status));
        
        if (Boolean.TRUE.equals(sendPaymentLink) && paymentLinkUrl != null && !paymentLinkUrl.isBlank()) {
            if ("EMAIL".equalsIgnoreCase(paymentMethod) || "BOTH".equalsIgnoreCase(paymentMethod)) {
                String toEmail = savedLead.getContact().getEmail();
                if (toEmail != null && !toEmail.isBlank()) {
                    emailService.sendPaymentLinkEmail(toEmail, paymentLinkUrl, savedLead.getDealValue());
                } else {
                    log.warn("Cannot send email payment link, contact has no email.");
                }
            }
            if ("WHATSAPP".equalsIgnoreCase(paymentMethod) || "BOTH".equalsIgnoreCase(paymentMethod)) {
                com.chatcrmlite.backend.models.WhatsAppConfig config = whatsappConfigRepository.findByTenantId(owner.getTenant().getId()).stream().findFirst().orElse(null);
                if (config != null) {
                    String amountText = savedLead.getDealValue() != null ? String.format("₹%,.2f", savedLead.getDealValue()) : "the agreed amount";
                    String message = String.format("Hi, your deal for *%s* has been approved!\n\nPlease complete your payment securely using the link below:\n%s", amountText, paymentLinkUrl);
                    whatsAppOutboundService.sendText(savedLead.getContact(), message, config, owner);
                } else {
                    log.warn("Cannot send WhatsApp payment link, tenant has no WhatsApp Config.");
                }
            }
        }

        return savedLead;
    }

    @Override
    @Transactional
    public EnquiryDTO addEnquiry(UUID leadId, EnquiryRequest req, User owner) {
        Lead lead = findOwnedLead(leadId, owner);
        return leadEnquiryService.addEnquiry(lead, req);
    }

    @Override
    @Transactional
    public EnquiryDTO updateEnquiry(UUID leadId, String enquiryId, EnquiryRequest req, User owner) {
        Lead lead = findOwnedLead(leadId, owner);
        return leadEnquiryService.updateEnquiry(lead, enquiryId, req);
    }

    @Override
    @Transactional
    public void deleteEnquiry(UUID leadId, String enquiryId, User owner) {
        Lead lead = findOwnedLead(leadId, owner);
        leadEnquiryService.deleteEnquiry(lead, enquiryId);
    }

    @Override
    public List<EnquiryDTO> getEnquiries(UUID leadId, User owner) {
        Lead lead = findOwnedLead(leadId, owner);
        return leadEnquiryService.getEnquiries(lead);
    }

    @Override
    @CacheEvict(value = {"leadsByContact", "latestLeadByContact", "leadsByUser", "revenueReport"}, allEntries = true)
    @Transactional
    public Lead updateDealInfo(UUID leadId, DealUpdateDTO dto, User owner) {
        Lead lead = findOwnedLead(leadId, owner);
        if (dto.getDealValue()     != null) lead.setDealValue(dto.getDealValue());
        if (dto.getDealLabel()     != null) lead.setDealLabel(dto.getDealLabel());
        if (dto.getCurrency()      != null) lead.setCurrency(dto.getCurrency());
        if (dto.getPaymentStatus() != null) {
            lead.setPaymentStatus(Lead.PaymentStatus.valueOf(dto.getPaymentStatus()));
        }
        lead.setLastActivity(LocalDateTime.now());
        Lead saved = leadRepository.save(lead);
        initializeLead(saved);
        return saved;
    }

    @Override
    @Cacheable(value = "revenueReport", key = "#owner.id")
    public RevenueReportDTO getRevenueReport(User owner) {
        List<Object[]> rawData;
        if (isAdmin(owner)) {
            rawData = leadRepository.calculateTenantRevenueReportRaw();
        } else {
            rawData = leadRepository.calculateRevenueReportRaw(owner.getId());
        }
        
        if (rawData == null || rawData.isEmpty() || rawData.get(0) == null) {
            return new RevenueReportDTO(java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, 0L, 0L, 0L, "INR");
        }
        
        Object[] row = rawData.get(0);
        java.math.BigDecimal totalRevenue = row[0] != null ? new java.math.BigDecimal(row[0].toString()) : java.math.BigDecimal.ZERO;
        java.math.BigDecimal paidRevenue = row[1] != null ? new java.math.BigDecimal(row[1].toString()) : java.math.BigDecimal.ZERO;
        java.math.BigDecimal pendingRevenue = row[2] != null ? new java.math.BigDecimal(row[2].toString()) : java.math.BigDecimal.ZERO;
        Long totalDeals = row[3] != null ? Long.parseLong(row[3].toString()) : 0L;
        Long paidDeals = row[4] != null ? Long.parseLong(row[4].toString()) : 0L;
        Long pendingDeals = row[5] != null ? Long.parseLong(row[5].toString()) : 0L;
        
        return new RevenueReportDTO(totalRevenue, paidRevenue, pendingRevenue, totalDeals, paidDeals, pendingDeals, "INR");
    }

    @Override
    @Transactional
    public void appendEnquiryToLead(Lead lead, String message, String type, String source, java.util.Map<String, String> collectedData) {
        leadEnquiryService.appendEnquiry(lead, message, type, source, collectedData);
        // Calculate lead score asynchronously or after append to evaluate new data
        if (leadScoringService != null) {
            leadScoringService.calculateAndEvaluate(lead);
            leadRepository.save(lead);
        }
    }

    @Override
    @Transactional
    public Lead rescoreLead(UUID leadId, User owner) {
        Lead lead = findOwnedLead(leadId, owner);
        if (leadScoringService != null) {
            leadScoringService.calculateAndEvaluate(lead);
            Lead saved = leadRepository.save(lead);
            initializeLead(saved);
            return saved;
        }
        return lead;
    }

    // ── Notes ─────────────────────────────────────────────────────────────

    @org.springframework.beans.factory.annotation.Autowired
    private com.chatcrmlite.backend.repositories.LeadNoteRepository leadNoteRepository;

    @org.springframework.beans.factory.annotation.Autowired
    private com.chatcrmlite.backend.repositories.LeadAttachmentRepository leadAttachmentRepository;

    @org.springframework.beans.factory.annotation.Autowired
    private com.chatcrmlite.backend.repositories.LeadActivityRepository leadActivityRepository;

    @org.springframework.beans.factory.annotation.Autowired
    private com.chatcrmlite.backend.repositories.UserRepository userRepository;

    @Override
    @Transactional
    public com.chatcrmlite.backend.dto.LeadNoteResponseDTO addNote(UUID leadId, String content, User caller) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Note content cannot be empty.");
        }
        Lead lead = findOwnedLead(leadId, caller);

        com.chatcrmlite.backend.models.LeadNote note = com.chatcrmlite.backend.models.LeadNote.builder()
                .lead(lead)
                .author(caller)
                .tenant(caller.getTenant())
                .content(content.trim())
                .createdAt(LocalDateTime.now())
                .deleted(false)
                .build();

        com.chatcrmlite.backend.models.LeadNote saved = leadNoteRepository.save(note);
        logActivity(lead, caller, com.chatcrmlite.backend.models.LeadActivity.ActivityType.NOTE_ADDED, "{\"noteId\":\"" + saved.getId() + "\"}");
        return com.chatcrmlite.backend.dto.LeadNoteResponseDTO.from(saved);
    }

    @Override
    public Page<com.chatcrmlite.backend.dto.LeadNoteResponseDTO> getNotesPaged(UUID leadId, int page, int size, User caller) {
        Lead lead = findOwnedLead(leadId, caller);
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<com.chatcrmlite.backend.models.LeadNote> notes = leadNoteRepository.findByLeadAndTenantAndDeletedFalseOrderByCreatedAtDesc(lead, caller.getTenant(), pageable);
        return notes.map(com.chatcrmlite.backend.dto.LeadNoteResponseDTO::from);
    }

    @Override
    @Transactional
    public void softDeleteNote(UUID leadId, UUID noteId, User caller) {
        // Authorization: Only OWNER or ADMIN can delete notes
        if (caller.getRole() != User.Role.OWNER && caller.getRole() != User.Role.ADMIN) {
            throw new IllegalArgumentException("Unauthorized: Only Tenant Owners and Admins can delete notes.");
        }
        Lead lead = findOwnedLead(leadId, caller);
        com.chatcrmlite.backend.models.LeadNote note = leadNoteRepository.findByIdAndLeadAndTenantAndDeletedFalse(noteId, lead, caller.getTenant())
                .orElseThrow(() -> new IllegalArgumentException("Note not found or already deleted"));

        note.setDeleted(true);
        note.setDeletedAt(LocalDateTime.now());
        note.setDeletedBy(caller);
        leadNoteRepository.save(note);
    }

    // ── Attachments ───────────────────────────────────────────────────────

    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB
    private static final java.util.Set<String> ALLOWED_MIME_TYPES = java.util.Set.of(
        "application/pdf", "image/png", "image/jpeg", "image/jpg",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/msword"
    );

    @org.springframework.beans.factory.annotation.Autowired
    private com.chatcrmlite.backend.services.storage.CloudinaryStorageService cloudinaryStorageService;

    @Override
    @Transactional
    public com.chatcrmlite.backend.dto.LeadAttachmentResponseDTO uploadAttachment(UUID leadId, org.springframework.web.multipart.MultipartFile file, User caller) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File cannot be empty.");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("File size exceeds maximum limit of 10MB.");
        }

        String mimeType = file.getContentType();
        if (mimeType == null || !ALLOWED_MIME_TYPES.contains(mimeType.toLowerCase())) {
            throw new IllegalArgumentException("File type not allowed. Allowed types: PDF, PNG, JPEG, DOCX.");
        }

        Lead lead = findOwnedLead(leadId, caller);

        // Sanitize original filename (prevent path traversal)
        String originalName = file.getOriginalFilename();
        if (originalName == null) originalName = "attachment";
        String sanitizedFilename = java.nio.file.Paths.get(originalName).getFileName().toString().replaceAll("[^a-zA-Z0-9._-]", "_");

        String checksum = "";
        try {
            byte[] bytes = file.getBytes();
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            checksum = sb.toString();
        } catch (Exception e) {
            log.warn("Failed to compute SHA-256 checksum: {}", e.getMessage());
        }

        String storagePath;
        com.chatcrmlite.backend.models.LeadAttachment.StorageType storageType;

        if (cloudinaryStorageService != null && cloudinaryStorageService.isConfigured()) {
            String key = cloudinaryStorageService.buildTenantKey(caller.getTenant().getId(), "leads/" + lead.getId(), sanitizedFilename);
            try {
                storagePath = cloudinaryStorageService.uploadFile(key, file);
                storageType = com.chatcrmlite.backend.models.LeadAttachment.StorageType.CLOUDINARY;
                log.info("Uploaded lead attachment to Cloudinary: {}", key);
            } catch (Exception e) {
                log.error("Failed to upload file to Cloudinary, falling back to local storage: {}", e.getMessage());
                storagePath = saveToLocalStorage(caller.getTenant().getId().toString(), lead.getId().toString(), sanitizedFilename, file);
                storageType = com.chatcrmlite.backend.models.LeadAttachment.StorageType.LOCAL;
            }
        } else {
            storagePath = saveToLocalStorage(caller.getTenant().getId().toString(), lead.getId().toString(), sanitizedFilename, file);
            storageType = com.chatcrmlite.backend.models.LeadAttachment.StorageType.LOCAL;
        }

        com.chatcrmlite.backend.models.LeadAttachment attachment = com.chatcrmlite.backend.models.LeadAttachment.builder()
                .lead(lead)
                .uploader(caller)
                .tenant(caller.getTenant())
                .fileName(sanitizedFilename)
                .fileSize(file.getSize())
                .fileType(mimeType)
                .storagePath(storagePath)
                .storageType(storageType)
                .checksumSha256(checksum)
                .createdAt(LocalDateTime.now())
                .deleted(false)
                .build();

        com.chatcrmlite.backend.models.LeadAttachment saved = leadAttachmentRepository.save(attachment);
        logActivity(lead, caller, com.chatcrmlite.backend.models.LeadActivity.ActivityType.FILE_UPLOADED, "{\"fileName\":\"" + sanitizedFilename + "\",\"storageType\":\"" + storageType + "\"}");
        return com.chatcrmlite.backend.dto.LeadAttachmentResponseDTO.from(saved);
    }

    private String saveToLocalStorage(String tenantId, String leadId, String sanitizedFilename, org.springframework.web.multipart.MultipartFile file) {
        String storageDir = "uploads/leads/" + tenantId + "/" + leadId;
        java.io.File dir = new java.io.File(storageDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        String storedFileName = UUID.randomUUID() + "_" + sanitizedFilename;
        java.io.File destFile = new java.io.File(dir, storedFileName);
        try {
            file.transferTo(destFile);
            return destFile.getAbsolutePath();
        } catch (Exception e) {
            throw new RuntimeException("Failed to store file attachment locally: " + e.getMessage());
        }
    }

    @Override
    public Page<com.chatcrmlite.backend.dto.LeadAttachmentResponseDTO> getAttachmentsPaged(UUID leadId, int page, int size, User caller) {
        Lead lead = findOwnedLead(leadId, caller);
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<com.chatcrmlite.backend.models.LeadAttachment> attachments = leadAttachmentRepository.findByLeadAndTenantAndDeletedFalseOrderByCreatedAtDesc(lead, caller.getTenant(), pageable);
        return attachments.map(com.chatcrmlite.backend.dto.LeadAttachmentResponseDTO::from);
    }

    @Override
    public com.chatcrmlite.backend.models.LeadAttachment getAttachmentEntity(UUID leadId, UUID attachmentId, User caller) {
        Lead lead = findOwnedLead(leadId, caller);
        return leadAttachmentRepository.findByIdAndLeadAndTenantAndDeletedFalse(attachmentId, lead, caller.getTenant())
                .orElseThrow(() -> new IllegalArgumentException("Attachment not found"));
    }

    @Override
    @Transactional
    public void softDeleteAttachment(UUID leadId, UUID attachmentId, User caller) {
        // Authorization: Only OWNER or ADMIN can delete attachments
        if (caller.getRole() != User.Role.OWNER && caller.getRole() != User.Role.ADMIN) {
            throw new IllegalArgumentException("Unauthorized: Only Tenant Owners and Admins can delete attachments.");
        }
        Lead lead = findOwnedLead(leadId, caller);
        com.chatcrmlite.backend.models.LeadAttachment attachment = leadAttachmentRepository.findByIdAndLeadAndTenantAndDeletedFalse(attachmentId, lead, caller.getTenant())
                .orElseThrow(() -> new IllegalArgumentException("Attachment not found or already deleted"));

        attachment.setDeleted(true);
        attachment.setDeletedAt(LocalDateTime.now());
        attachment.setDeletedBy(caller);
        leadAttachmentRepository.save(attachment);
    }

    // ── Reassign & Activity ───────────────────────────────────────────────

    @Override
    @Transactional
    public Lead reassignLeadOwner(UUID leadId, UUID newOwnerId, User caller) {
        Lead lead = findOwnedLead(leadId, caller);
        User newOwner = userRepository.findById(newOwnerId)
                .orElseThrow(() -> new IllegalArgumentException("Target user not found"));

        if (!newOwner.getTenant().getId().equals(caller.getTenant().getId())) {
            throw new IllegalArgumentException("Cannot reassign lead to a user from another tenant.");
        }

        lead.setOwner(newOwner);
        Lead saved = leadRepository.save(lead);
        logActivity(saved, caller, com.chatcrmlite.backend.models.LeadActivity.ActivityType.LEAD_REASSIGNED, "{\"newOwner\":\"" + (newOwner.getDisplayName() != null && !newOwner.getDisplayName().isBlank() ? newOwner.getDisplayName() : newOwner.getEmail()) + "\"}");
        initializeLead(saved);
        return saved;
    }

    @Override
    public Page<com.chatcrmlite.backend.dto.LeadActivityResponseDTO> getActivitiesPaged(UUID leadId, int page, int size, User caller) {
        Lead lead = findOwnedLead(leadId, caller);
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<com.chatcrmlite.backend.models.LeadActivity> activities = leadActivityRepository.findByLeadAndTenantOrderByCreatedAtDesc(lead, caller.getTenant(), pageable);
        return activities.map(com.chatcrmlite.backend.dto.LeadActivityResponseDTO::from);
    }

    @Override
    @Transactional
    public void logActivity(Lead lead, User actor, com.chatcrmlite.backend.models.LeadActivity.ActivityType type, String metadataJson) {
        if (lead == null || actor == null || type == null) return;
        com.chatcrmlite.backend.models.LeadActivity activity = com.chatcrmlite.backend.models.LeadActivity.builder()
                .lead(lead)
                .actor(actor)
                .tenant(actor.getTenant())
                .type(type)
                .metadataJson(metadataJson != null ? metadataJson : "{}")
                .createdAt(LocalDateTime.now())
                .build();
        leadActivityRepository.save(activity);
    }

    @Override
    @Transactional
    public Lead claimLead(UUID leadId, User caller) {
        if (caller.getRole() != User.Role.AGENT && caller.getRole() != User.Role.OWNER && caller.getRole() != User.Role.ADMIN) {
            throw new IllegalArgumentException("Unauthorized to claim lead.");
        }

        // 1. Lock the user to prevent concurrent TOCTOU race conditions when checking capacity
        User agent = userRepository.findByIdWithPessimisticWriteLock(caller.getId())
                .orElseThrow(() -> new IllegalArgumentException("Agent not found"));
        
        if (agent.getTenant() == null) {
            throw new IllegalArgumentException("Agent has no tenant assigned.");
        }

        // 2. Check daily capacity
        int dailyLimit = agent.getDailyLeadLimit() != null ? agent.getDailyLeadLimit() 
                : (agent.getTenant().getDefaultDailyLeadLimit() != null ? agent.getTenant().getDefaultDailyLeadLimit() : 20);

        LocalDateTime todayStart = LocalDateTime.now().with(java.time.LocalTime.MIN);
        // Note: We could count from LeadAssignmentRepository here. 
        // For simplicity, we just count the user's assignments today.
        long todayCount = leadAssignmentRepository.findAll().stream() // In prod, this should be a DB count query, but for now we'll do a quick count.
                .filter(la -> la.getAgent().getId().equals(agent.getId()) && la.getAssignedAt().isAfter(todayStart))
                .count();

        if (todayCount >= dailyLimit) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT, "Daily lead limit reached.");
        }

        // 3. Atomic Assignment Update
        LocalDateTime now = LocalDateTime.now();
        int rowsUpdated = leadRepository.atomicAssignLead(
                leadId, agent.getId(), agent.getTenant().getId(), now, AssignmentSource.MANUAL.name());

        if (rowsUpdated == 0) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT, "Lead is no longer available for claiming.");
        }

        // 4. Record history
        Lead lead = leadRepository.findById(leadId).orElseThrow();
        LeadAssignment history = new LeadAssignment(lead, agent, now, agent, AssignmentSource.MANUAL);
        leadAssignmentRepository.save(history);

        logActivity(lead, agent, com.chatcrmlite.backend.models.LeadActivity.ActivityType.LEAD_REASSIGNED, "{\"newOwner\":\"" + agent.getEmail() + "\"}");
        
        initializeLead(lead);
        return lead;
    }

    @Override
    @Transactional
    public void autoAssignLead(UUID leadId, com.chatcrmlite.backend.models.Tenant tenant) {
        Lead lead = leadRepository.findById(leadId).orElse(null);
        if (lead == null) return;
        
        int defaultLimit = tenant.getDefaultDailyLeadLimit() != null ? tenant.getDefaultDailyLeadLimit() : 20;
        LocalDateTime todayStart = LocalDateTime.now().with(java.time.LocalTime.MIN);
        
        java.util.Optional<UUID> bestAgentIdOpt = leadRepository.findBestEligibleAgentForTenant(tenant.getId(), defaultLimit, todayStart);
        
        if (bestAgentIdOpt.isPresent()) {
            User agent = userRepository.findById(bestAgentIdOpt.get()).orElseThrow();
            
            LocalDateTime now = LocalDateTime.now();
            int rowsUpdated = leadRepository.atomicAssignLead(
                    leadId, agent.getId(), tenant.getId(), now, AssignmentSource.AUTO.name());

            if (rowsUpdated == 1) {
                LeadAssignment history = new LeadAssignment(lead, agent, now, null, AssignmentSource.AUTO);
                leadAssignmentRepository.save(history);
                
                logActivity(lead, agent, com.chatcrmlite.backend.models.LeadActivity.ActivityType.LEAD_REASSIGNED, "{\"newOwner\":\"" + agent.getEmail() + "\",\"source\":\"AUTO\"}");
            }
        } else {
            // No eligible agents found. Mark as LIMIT_REACHED
            lead.setAssignmentStatus(com.chatcrmlite.backend.models.LeadAssignmentStatus.LIMIT_REACHED);
            leadRepository.save(lead);
        }
    }
}
