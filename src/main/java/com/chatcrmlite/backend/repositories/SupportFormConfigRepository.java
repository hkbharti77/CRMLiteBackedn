package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.SupportFormConfig;
import com.chatcrmlite.backend.models.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SupportFormConfigRepository extends JpaRepository<SupportFormConfig, UUID> {
    
    Optional<SupportFormConfig> findByOwner(User owner);
    
    Optional<SupportFormConfig> findByOwner_Id(UUID ownerId);
}
