package com.chatcrmlite.backend.services.lead;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.repositories.LeadRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class LeadValidator {

    private final LeadRepository leadRepository;

    /** Validate lead creation scenarios and business rules */
    public void validateLeadCreation(Contact contact, User owner, String enquiryType) {
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
        long activeLeadCount = getActiveLeadCountByContactId(contact, owner);
        if (activeLeadCount >= 10) { // Configurable limit
            throw new IllegalStateException("Contact has too many active leads (" + activeLeadCount + "). Maximum allowed: 10");
        }
    }

    private long getActiveLeadCountByContactId(Contact contact, User owner) {
        List<Lead.LeadStatus> excludedStatuses = List.of(
                Lead.LeadStatus.BOOKED,
                Lead.LeadStatus.CLOSED_WON,
                Lead.LeadStatus.CLOSED_LOST);
        
        return leadRepository.countByContactAndOwnerAndStatusNotIn(contact, owner, excludedStatuses);
    }
}
