package com.chatcrmlite.backend.security;

import com.chatcrmlite.backend.models.EmailProvider;
import com.chatcrmlite.backend.repositories.EmailProviderRepository;
import com.chatcrmlite.backend.services.email.EmailProviderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
public class CustomEmailProviderSecurityTest {

    @Autowired
    private EmailProviderService emailProviderService;

    @Autowired
    private EmailProviderRepository emailProviderRepository;

    @Test
    public void saveProvider_rejectsCrossTenantModification() {
        // Create an existing provider for Tenant A
        EmailProvider existing = new EmailProvider();
        existing.setId(UUID.randomUUID().toString());
        existing.setBusinessId("tenant-A");
        existing.setName("Tenant A Provider");
        existing.setProviderType("SMTP");
        existing.setFromEmail("test@tenant-a.com");
        existing.setCredentialsPayload("{}");
        emailProviderRepository.save(existing);

        // Attempt to update it as Tenant B
        EmailProvider attackerPayload = new EmailProvider();
        attackerPayload.setId(existing.getId());
        attackerPayload.setBusinessId("tenant-B");
        attackerPayload.setName("Hijacked Provider");
        attackerPayload.setProviderType("SMTP");
        attackerPayload.setFromEmail("attacker@tenant-b.com");
        attackerPayload.setCredentialsPayload("{}");

        assertThrows(AccessDeniedException.class, () -> {
            emailProviderService.saveProvider(attackerPayload);
        });
    }
}
