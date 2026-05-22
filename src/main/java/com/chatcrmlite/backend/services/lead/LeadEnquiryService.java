package com.chatcrmlite.backend.services.lead;

import com.chatcrmlite.backend.dto.EnquiryDTO;
import com.chatcrmlite.backend.dto.EnquiryRequest;
import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.LeadEnquiry;
import com.chatcrmlite.backend.repositories.LeadEnquiryRepository;
import com.chatcrmlite.backend.repositories.LeadRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LeadEnquiryService {

    private final LeadEnquiryRepository leadEnquiryRepository;
    private final LeadRepository leadRepository;

    @CacheEvict(value = {"leadsByContact", "latestLeadByContact"}, allEntries = true)
    @Transactional
    public EnquiryDTO addEnquiry(Lead lead, EnquiryRequest req) {
        LeadEnquiry enquiry = LeadEnquiry.builder()
                .lead(lead)
                .type(req.getType() != null ? req.getType() : "MANUAL")
                .message(req.getMessage())
                .source(req.getSource() != null ? req.getSource() : "Manual Entry")
                .status(req.getStatus() != null ? req.getStatus() : "OPEN")
                .build();

        LeadEnquiry saved = leadEnquiryRepository.save(enquiry);
        
        updateLeadActivity(lead);
        
        return mapToDTO(saved);
    }

    @CacheEvict(value = {"leadsByContact", "latestLeadByContact"}, allEntries = true)
    @Transactional
    public EnquiryDTO updateEnquiry(Lead lead, String enquiryId, EnquiryRequest req) {
        UUID enquiryUuid = UUID.fromString(enquiryId);
        
        LeadEnquiry enquiry = leadEnquiryRepository.findById(enquiryUuid)
                .filter(e -> e.getLead().getId().equals(lead.getId()))
                .orElseThrow(() -> new RuntimeException("Enquiry not found: " + enquiryId));

        if (req.getMessage() != null) enquiry.setMessage(req.getMessage());
        if (req.getType()    != null) enquiry.setType(req.getType());
        if (req.getSource()  != null) enquiry.setSource(req.getSource());
        if (req.getStatus()  != null) enquiry.setStatus(req.getStatus());

        LeadEnquiry saved = leadEnquiryRepository.save(enquiry);
        
        updateLeadActivity(lead);
        
        return mapToDTO(saved);
    }

    @CacheEvict(value = {"leadsByContact", "latestLeadByContact"}, allEntries = true)
    @Transactional
    public void deleteEnquiry(Lead lead, String enquiryId) {
        UUID enquiryUuid = UUID.fromString(enquiryId);
        
        LeadEnquiry enquiry = leadEnquiryRepository.findById(enquiryUuid)
                .filter(e -> e.getLead().getId().equals(lead.getId()))
                .orElseThrow(() -> new RuntimeException("Enquiry not found: " + enquiryId));

        leadEnquiryRepository.delete(enquiry);
        
        updateLeadActivity(lead);
    }

    public List<EnquiryDTO> getEnquiries(Lead lead) {
        return leadEnquiryRepository.findAllByLeadOrderByCreatedAtDesc(lead).stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    @Transactional
    public void appendEnquiry(Lead lead, String message, String type, String source) {
        LeadEnquiry enquiry = LeadEnquiry.builder()
                .lead(lead)
                .type(type)
                .message(message)
                .source(source)
                .status("OPEN")
                .build();
        
        leadEnquiryRepository.save(enquiry);
        
        updateLeadActivity(lead);
    }

    private void updateLeadActivity(Lead lead) {
        leadRepository.updateLastActivity(lead.getId(), LocalDateTime.now());
    }

    private EnquiryDTO mapToDTO(LeadEnquiry enquiry) {
        return EnquiryDTO.builder()
                .id(enquiry.getId().toString())
                .type(enquiry.getType())
                .message(enquiry.getMessage())
                .source(enquiry.getSource())
                .status(enquiry.getStatus())
                .createdAt(enquiry.getCreatedAt().toString())
                .build();
    }
}
