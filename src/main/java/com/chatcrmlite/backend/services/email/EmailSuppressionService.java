package com.chatcrmlite.backend.services.email;

import com.chatcrmlite.backend.models.email.EmailSuppressionList;
import com.chatcrmlite.backend.models.email.EmailSuppressionList.SuppressionReason;
import com.chatcrmlite.backend.repositories.email.EmailSuppressionListRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EmailSuppressionService {

    private final EmailSuppressionListRepository suppressionListRepository;

    public String normalizeEmail(String email) {
        if (email == null) return null;
        return email.trim().toLowerCase();
    }

    public boolean isSuppressed(UUID tenantId, String email) {
        if (email == null) return false;
        String normalizedEmail = normalizeEmail(email);
        return suppressionListRepository.existsByTenantIdAndEmail(tenantId, normalizedEmail);
    }

    @Transactional
    public void addSuppression(UUID tenantId, String email, SuppressionReason reason, UUID sourceCampaignId, UUID createdBy) {
        if (email == null) return;
        String normalizedEmail = normalizeEmail(email);
        
        Optional<EmailSuppressionList> existing = suppressionListRepository.findByTenantIdAndEmail(tenantId, normalizedEmail);
        if (existing.isEmpty()) {
            EmailSuppressionList suppression = EmailSuppressionList.builder()
                .tenantId(tenantId)
                .email(normalizedEmail)
                .reason(reason)
                .sourceCampaignId(sourceCampaignId)
                .createdBy(createdBy)
                .build();
            suppressionListRepository.save(suppression);
        }
    }

    @Transactional(readOnly = true)
    public List<EmailSuppressionList> getSuppressions(UUID tenantId) {
        return suppressionListRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    @Transactional
    public boolean deleteSuppression(UUID tenantId, UUID id) {
        Optional<EmailSuppressionList> suppression = suppressionListRepository.findById(id);
        if (suppression.isPresent() && tenantId.equals(suppression.get().getTenantId())) {
            suppressionListRepository.delete(suppression.get());
            return true;
        }
        return false;
    }
}
