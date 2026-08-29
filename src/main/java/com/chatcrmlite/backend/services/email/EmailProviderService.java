package com.chatcrmlite.backend.services.email;

import com.chatcrmlite.backend.models.EmailProvider;
import com.chatcrmlite.backend.repositories.EmailProviderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailProviderService {

    private final EmailProviderRepository repository;
    private final ProviderFactory factory;

    @Transactional(readOnly = true)
    public List<EmailProvider> getProviders(String businessId) {
        return repository.findByBusinessId(businessId);
    }

    @Transactional(readOnly = true)
    public Optional<EmailProvider> getDefaultProvider(String businessId) {
        if (businessId == null || businessId.isBlank()) {
            return Optional.empty();
        }
        return repository.findByBusinessIdAndIsDefaultTrue(businessId);
    }

    @Transactional
    public EmailProvider saveProvider(EmailProvider provider) {
        if (provider.getId() == null || provider.getId().isEmpty()) {
            provider.setId(UUID.randomUUID().toString());
        } else {
            EmailProvider existing = repository.findById(provider.getId())
                    .orElseThrow(() -> new RuntimeException("Provider not found"));
            if (provider.getBusinessId() != null && !provider.getBusinessId().isEmpty() &&
                !existing.getBusinessId().equals(provider.getBusinessId())) {
                throw new org.springframework.security.access.AccessDeniedException("Cannot modify provider belonging to another business");
            }
            if (provider.getBusinessId() == null || provider.getBusinessId().isEmpty()) {
                provider.setBusinessId(existing.getBusinessId());
            }
        }
        
        // If this is the first provider for the business, make it default
        List<EmailProvider> existing = repository.findByBusinessId(provider.getBusinessId());
        if (existing.isEmpty()) {
            provider.setIsDefault(true);
        } else if (Boolean.TRUE.equals(provider.getIsDefault())) {
            // Unset other defaults
            existing.forEach(p -> {
                if (Boolean.TRUE.equals(p.getIsDefault()) && !p.getId().equals(provider.getId())) {
                    p.setIsDefault(false);
                    repository.save(p);
                }
            });
        }
        
        return repository.save(provider);
    }

    @Transactional
    public void deleteProvider(String id, String businessId) {
        repository.findByIdAndBusinessId(id, businessId).ifPresent(p -> {
            repository.delete(p);
            if (Boolean.TRUE.equals(p.getIsDefault())) {
                List<EmailProvider> remaining = repository.findByBusinessId(businessId);
                if (!remaining.isEmpty()) {
                    EmailProvider next = remaining.get(0);
                    next.setIsDefault(true);
                    repository.save(next);
                }
            }
        });
    }

    @Transactional
    public boolean testConnection(EmailProvider provider, String testEmail) {
        boolean success = false;
        String status = "ERROR";
        try {
            EmailSenderProvider sender = factory.getProvider(provider);
            sender.sendTestEmail(testEmail, provider.getFromEmail());
            status = "CONNECTED";
            success = true;
        } catch (Exception e) {
            log.error("Failed to verify connection for provider {}", provider.getProviderType(), e);
            status = "ERROR";
            success = false;
        }

        if (provider.getId() != null && !provider.getId().isEmpty()) {
            final String finalStatus = status;
            repository.findById(provider.getId()).ifPresent(existing -> {
                existing.setStatus(finalStatus);
                repository.save(existing);
            });
        }
        return success;
    }
}
