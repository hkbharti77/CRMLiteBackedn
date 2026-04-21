package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.WhatsAppConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface WhatsAppConfigRepository extends JpaRepository<WhatsAppConfig, UUID> {
    Optional<WhatsAppConfig> findByPhoneNumberId(String phoneNumberId);
    Optional<WhatsAppConfig> findByUserId(UUID userId);
    boolean existsByVerifyToken(String verifyToken);
}
