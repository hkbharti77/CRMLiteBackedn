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
    void validateLeadCreation(Contact contact, User owner, String enquiryType);
    List<Lead> getLeadsByContactId(UUID contactId, User owner);
    Lead getLatestLeadByContactId(UUID contactId, User owner);
    long getActiveLeadCountByContactId(UUID contactId, User owner);
    long getTotalLeadCount(User owner);
    long getLeadCountByStatus(Lead.LeadStatus status, User owner);
    Page<Lead> getLeadsByUserPaged(User user, int page, int size, Lead.LeadStatus status);
    Lead updateStatus(UUID leadId, Lead.LeadStatus status, User owner);
    EnquiryDTO addEnquiry(UUID leadId, EnquiryRequest req, User owner);
    EnquiryDTO updateEnquiry(UUID leadId, String enquiryId, EnquiryRequest req, User owner);
    void deleteEnquiry(UUID leadId, String enquiryId, User owner);
    List<EnquiryDTO> getEnquiries(UUID leadId, User owner);
    Lead updateDealInfo(UUID leadId, DealUpdateDTO dto, User owner);
    RevenueReportDTO getRevenueReport(User owner);
    void appendEnquiryToLead(Lead lead, String message, String type, String source);
}
