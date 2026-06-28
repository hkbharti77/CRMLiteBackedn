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
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.stream.Collectors;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;

@Service
@RequiredArgsConstructor
public class LeadEnquiryService {

    private final LeadEnquiryRepository leadEnquiryRepository;
    private final LeadRepository leadRepository;
    private final com.chatcrmlite.backend.services.FlowConfigService flowConfigService;
    private static final ObjectMapper objectMapper = new ObjectMapper();

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
    public void appendEnquiry(Lead lead, String message, String type, String source, Map<String, String> collectedData) {
        String finalSource = source;
        if (collectedData != null && collectedData.containsKey("source") && !collectedData.get("source").isBlank()) {
            finalSource = collectedData.get("source");
        }

        LeadEnquiry.LeadEnquiryBuilder builder = LeadEnquiry.builder()
                .lead(lead)
                .type(type)
                .message(message)
                .source(finalSource)
                .status("OPEN");

        if (collectedData != null) {
            builder.name(collectedData.get("name"));
            builder.email(collectedData.get("email"));
            builder.phone(collectedData.get("phone"));
            builder.company(collectedData.get("company"));
            String sc = collectedData.get("serviceCategory");
            if (sc == null) sc = collectedData.get("service_category");
            builder.serviceCategory(sc);

            String req = collectedData.get("requirement");
            if (req == null) req = collectedData.get("specific_requirement");
            
            // Fetch dynamic labels for custom fields
            java.util.Map<String, String> keyToLabel = new java.util.HashMap<>();
            try {
                java.util.List<com.chatcrmlite.backend.dto.flow.FlowFieldConfig> configs = 
                        flowConfigService.getConfigurableFields(lead.getOwner(), "lead");
                for (com.chatcrmlite.backend.dto.flow.FlowFieldConfig cfg : configs) {
                    if (cfg.getLabel() != null && !cfg.getLabel().isBlank()) {
                        keyToLabel.put(cfg.getKey(), cfg.getLabel());
                    }
                }
            } catch (Exception e) {
                // fallback if missing
            }

            // Collect unmapped extra fields into additionalDetails JSON
            Map<String, String> extrasMap = new HashMap<>();
            java.util.List<String> mappedKeys = java.util.List.of("name", "email", "phone", "company", "serviceCategory", "service_category", "requirement", "specific_requirement", "budget", "city", "country", "age", "gender", "address", "pincode", "preferred_date", "source");
            for (Map.Entry<String, String> entry : collectedData.entrySet()) {
                if (!mappedKeys.contains(entry.getKey()) && entry.getValue() != null && !entry.getValue().isBlank()) {
                    String displayLabel = keyToLabel.getOrDefault(entry.getKey(), entry.getKey());
                    extrasMap.put(displayLabel, entry.getValue());
                }
            }
            if (!extrasMap.isEmpty()) {
                try {
                    builder.additionalDetails(objectMapper.writeValueAsString(extrasMap));
                } catch (JsonProcessingException e) {
                    // Ignore mapping error
                }
                
                StringBuilder extrasStr = new StringBuilder();
                for (Map.Entry<String, String> entry : extrasMap.entrySet()) {
                    extrasStr.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
                }
                if (req == null) req = "";
                else req += "\n\n";
                req += "Additional Details:\n" + extrasStr.toString().trim();
            }

            builder.requirement(req);
            builder.budget(collectedData.get("budget"));
            builder.city(collectedData.get("city"));
            builder.country(collectedData.get("country"));
            builder.age(collectedData.get("age"));
            builder.gender(collectedData.get("gender"));
            builder.address(collectedData.get("address"));
            builder.pincode(collectedData.get("pincode"));
            builder.preferredDate(collectedData.get("preferred_date"));
        }
        
        LeadEnquiry enquiry = builder.build();
        leadEnquiryRepository.save(enquiry);
        
        updateLeadActivity(lead);
    }

    private void updateLeadActivity(Lead lead) {
        leadRepository.updateLastActivity(lead.getId(), LocalDateTime.now());
    }

    private EnquiryDTO mapToDTO(LeadEnquiry enquiry) {
        Map<String, String> addDetails = null;
        if (enquiry.getAdditionalDetails() != null) {
            try {
                addDetails = objectMapper.readValue(enquiry.getAdditionalDetails(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {});
            } catch (JsonProcessingException e) {
                // Ignore parsing errors
            }
        }

        return EnquiryDTO.builder()
                .id(enquiry.getId() != null ? enquiry.getId().toString() : null)
                .type(enquiry.getType())
                .message(enquiry.getMessage())
                .source(enquiry.getSource())
                .status(enquiry.getStatus())
                .name(enquiry.getName())
                .email(enquiry.getEmail())
                .phone(enquiry.getPhone())
                .company(enquiry.getCompany())
                .serviceCategory(enquiry.getServiceCategory())
                .requirement(enquiry.getRequirement())
                .budget(enquiry.getBudget())
                .city(enquiry.getCity())
                .country(enquiry.getCountry())
                .age(enquiry.getAge())
                .gender(enquiry.getGender())
                .address(enquiry.getAddress())
                .pincode(enquiry.getPincode())
                .preferredDate(enquiry.getPreferredDate())
                .additionalDetails(addDetails)
                .createdAt(enquiry.getCreatedAt().toString())
                .build();
    }
}
