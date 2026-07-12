package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.models.EmailTemplate;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.EmailTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EmailTemplateService {

    private final EmailTemplateRepository emailTemplateRepository;

    public List<EmailTemplate> getTemplatesByTenant(Tenant tenant) {
        return emailTemplateRepository.findAllByTenant(tenant);
    }

    public EmailTemplate getTemplateById(UUID id, Tenant tenant) {
        return emailTemplateRepository.findById(id)
                .filter(template -> template.getTenant().getId().equals(tenant.getId()))
                .orElseThrow(() -> new RuntimeException("Template not found"));
    }

    public EmailTemplate createTemplate(EmailTemplate template, User owner) {
        template.setOwner(owner);
        return emailTemplateRepository.save(template);
    }

    public EmailTemplate updateTemplate(UUID id, EmailTemplate updatedTemplate, User owner) {
        EmailTemplate existingTemplate = getTemplateById(id, owner.getTenant());
        existingTemplate.setName(updatedTemplate.getName());
        existingTemplate.setSubject(updatedTemplate.getSubject());
        existingTemplate.setContent(updatedTemplate.getContent());
        existingTemplate.setInterestCategory(updatedTemplate.getInterestCategory());
        return emailTemplateRepository.save(existingTemplate);
    }

    public void deleteTemplate(UUID id, Tenant tenant) {
        EmailTemplate template = getTemplateById(id, tenant);
        emailTemplateRepository.delete(template);
    }

    public Optional<EmailTemplate> findTemplateForInterest(Tenant tenant, String interestCategory) {
        if (interestCategory != null && !interestCategory.isBlank()) {
            Optional<EmailTemplate> specificTemplate = emailTemplateRepository.findByTenantAndInterestCategory(tenant, interestCategory);
            if (specificTemplate.isPresent()) {
                return specificTemplate;
            }
        }
        return emailTemplateRepository.findByTenantAndInterestCategoryIsNull(tenant);
    }
}
