package com.chatcrmlite.backend.repositories.email;

import com.chatcrmlite.backend.models.email.EmailProviderEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmailProviderEventRepository extends JpaRepository<EmailProviderEvent, UUID> {
    boolean existsByProviderAndProviderEventId(String provider, String providerEventId);
}
