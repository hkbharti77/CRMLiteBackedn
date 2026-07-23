package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.WhatsAppTemplateSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WhatsAppTemplateSnapshotRepository extends JpaRepository<WhatsAppTemplateSnapshot, UUID> {
    List<WhatsAppTemplateSnapshot> findByOriginalTemplateIdOrderByVersionDesc(UUID originalTemplateId);
    Optional<WhatsAppTemplateSnapshot> findFirstByOriginalTemplateIdOrderByVersionDesc(UUID originalTemplateId);
}
