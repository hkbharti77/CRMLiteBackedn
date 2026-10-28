package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "knowledge_documents")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeDocument extends BaseTenantEntity implements Persistable<UUID> {

    @Id
    private UUID id;

    @Transient
    @Builder.Default
    private boolean isNew = true;

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    @Column(nullable = false, length = 512)
    private String name;

    @Column(name = "source_type", nullable = false, length = 64)
    @Builder.Default
    private String sourceType = "UPLOAD";

    @Column(name = "source_url", length = 2048)
    private String sourceUrl;

    @Column(name = "active_version", nullable = false)
    @Builder.Default
    private Integer activeVersion = 1;

    @Column(nullable = false, length = 32)
    @Builder.Default
    private String status = "ACTIVE";

    @Column(name = "total_chunks")
    @Builder.Default
    private Integer totalChunks = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @PrePersist
    protected void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
