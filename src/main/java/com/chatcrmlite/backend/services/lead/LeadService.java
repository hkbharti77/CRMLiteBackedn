package com.chatcrmlite.backend.services.lead;

import com.chatcrmlite.backend.dto.DealUpdateDTO;
import com.chatcrmlite.backend.dto.EnquiryDTO;
import com.chatcrmlite.backend.dto.EnquiryRequest;
import com.chatcrmlite.backend.dto.RevenueReportDTO;
import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.Contact;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.UUID;

public interface LeadService {
    Lead getLeadById(UUID leadId, User owner);
    Lead getLeadByLeadNumber(String leadNumber, User owner);
    void validateLeadCreation(Contact contact, User owner, String enquiryType);
    List<Lead> getLeadsByContactId(UUID contactId, User owner);
    Lead getLatestLeadByContactId(UUID contactId, User owner);
    long getActiveLeadCountByContactId(UUID contactId, User owner);
    long getTotalLeadCount(User owner);
    long getLeadCountByStatus(Lead.LeadStatus status, User owner);
    Page<Lead> getLeadsByUserPaged(User user, int page, int size, Lead.LeadStatus status, String search);
    Lead updateStatus(UUID leadId, Lead.LeadStatus status, java.math.BigDecimal dealValue, String lostReason, Lead.PaymentStatus paymentStatus, Boolean sendPaymentLink, String paymentMethod, String paymentLinkUrl, User owner);
    EnquiryDTO addEnquiry(UUID leadId, EnquiryRequest req, User owner);
    EnquiryDTO updateEnquiry(UUID leadId, String enquiryId, EnquiryRequest req, User owner);
    void deleteEnquiry(UUID leadId, String enquiryId, User owner);
    List<EnquiryDTO> getEnquiries(UUID leadId, User owner);
    Lead updateDealInfo(UUID leadId, DealUpdateDTO dto, User owner);
    RevenueReportDTO getRevenueReport(User owner);
    void appendEnquiryToLead(Lead lead, String message, String type, String source, java.util.Map<String, String> collectedData);
    Lead rescoreLead(UUID leadId, User owner);

    // Notes, Attachments, Activities, Reassign
    com.chatcrmlite.backend.dto.LeadNoteResponseDTO addNote(UUID leadId, String content, User caller);
    Page<com.chatcrmlite.backend.dto.LeadNoteResponseDTO> getNotesPaged(UUID leadId, int page, int size, User caller);
    void softDeleteNote(UUID leadId, UUID noteId, User caller);

    com.chatcrmlite.backend.dto.LeadAttachmentResponseDTO uploadAttachment(UUID leadId, org.springframework.web.multipart.MultipartFile file, User caller);
    Page<com.chatcrmlite.backend.dto.LeadAttachmentResponseDTO> getAttachmentsPaged(UUID leadId, int page, int size, User caller);
    com.chatcrmlite.backend.models.LeadAttachment getAttachmentEntity(UUID leadId, UUID attachmentId, User caller);
    void softDeleteAttachment(UUID leadId, UUID attachmentId, User caller);

    Lead reassignLeadOwner(UUID leadId, UUID newOwnerId, User caller);
    Page<com.chatcrmlite.backend.dto.LeadActivityResponseDTO> getActivitiesPaged(UUID leadId, int page, int size, User caller);
    void logActivity(Lead lead, User actor, com.chatcrmlite.backend.models.LeadActivity.ActivityType type, String metadataJson);

    Lead claimLead(UUID leadId, User caller);
    void autoAssignLead(UUID leadId, com.chatcrmlite.backend.models.Tenant tenant);
}
