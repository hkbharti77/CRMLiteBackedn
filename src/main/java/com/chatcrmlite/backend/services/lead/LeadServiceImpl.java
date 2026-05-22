package com.chatcrmlite.backend.services.lead;

import com.chatcrmlite.backend.dto.DealUpdateDTO;
import com.chatcrmlite.backend.dto.EnquiryDTO;
import com.chatcrmlite.backend.dto.EnquiryRequest;
import com.chatcrmlite.backend.dto.RevenueReportDTO;
import com.chatcrmlite.backend.event.LeadStatusChangedEvent;
import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.Contact;
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
public class LeadServiceImpl implements LeadService {

    private final LeadRepository leadRepository;
    private final ContactRepository contactRepository;
    private final LeadValidator leadValidator;
    private final LeadEnquiryService leadEnquiryService;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public Lead getLeadById(UUID leadId, User owner) {
        return findOwnedLead(leadId, owner);
    }

    private Lead findOwnedLead(UUID leadId, User owner) {
        return leadRepository.findById(leadId)
                .filter(l -> l.getOwner().getId().equals(owner.getId()))
                .orElseThrow(() -> new RuntimeException("Lead not found"));
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
        return leadRepository.findAllByContactAndOwnerOptimized(contact, owner);
    }

    @Override
    @Cacheable(value = "latestLeadByContact", key = "#contactId + '_' + #owner.id")
    public Lead getLatestLeadByContactId(UUID contactId, User owner) {
        Contact contact = contactRepository.findById(contactId)
                .orElseThrow(() -> new RuntimeException("Contact not found"));
        return leadRepository.findAllByContact(contact).stream()
                .filter(l -> l.getOwner().getId().equals(owner.getId()))
                .max(java.util.Comparator.comparing(Lead::getCreatedAt))
                .orElseThrow(() -> new RuntimeException("No lead found for this contact"));
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
        
        return leadRepository.countByContactAndOwnerAndStatusNotIn(contact, owner, excludedStatuses);
    }

    @Override
    public Page<Lead> getLeadsByUserPaged(User user, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "lastActivity"));
        return leadRepository.findAllByOwnerPaged(user, pageable);
    }

    @Override
    @Cacheable(value = "leadsByUser", key = "#user.id")
    public List<Lead> getLeadsByUser(User user) {
        return leadRepository.findAllByOwnerWithContactAndTags(user);
    }

    @Override
    @Cacheable(value = "leadsByStatus", key = "#status + '_' + #user.id")
    public List<Lead> getLeadsByStatus(Lead.LeadStatus status, User user) {
        return leadRepository.findAllByStatusAndOwner(status, user);
    }

    @Override
    @CacheEvict(value = {"leadsByContact", "latestLeadByContact", "activeLeadCount", "leadsByUser", "leadsByStatus"}, allEntries = true)
    @Transactional
    public Lead updateStatus(UUID leadId, Lead.LeadStatus status, User owner) {
        Lead lead = findOwnedLead(leadId, owner);
        Lead.LeadStatus oldStatus = lead.getStatus();
        
        log.info("[Lead-Status] Updating lead {} from {} to {} for contact {} (owner: {})", 
                leadId, oldStatus, status, 
                lead.getContact().getWaId(), owner.getId());
        
        lead.setStatus(status);
        lead.setLastActivity(LocalDateTime.now());
        Lead savedLead = leadRepository.save(lead);
        
        eventPublisher.publishEvent(new LeadStatusChangedEvent(this, savedLead, oldStatus, status));
        
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
        return leadRepository.save(lead);
    }

    @Override
    @Cacheable(value = "revenueReport", key = "#owner.id")
    public RevenueReportDTO getRevenueReport(User owner) {
        return leadRepository.calculateRevenueReport(owner);
    }

    @Override
    @Transactional
    public void appendEnquiryToLead(Lead lead, String message, String type, String source) {
        leadEnquiryService.appendEnquiry(lead, message, type, source);
    }
}
