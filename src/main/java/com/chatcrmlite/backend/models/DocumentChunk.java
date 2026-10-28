package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "document_chunks")
public class DocumentChunk extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "content_hash", nullable = false)
    private String contentHash;

    @Column(name = "embedding", columnDefinition = "vector", nullable = false)
    @org.hibernate.annotations.ColumnTransformer(write = "?::vector", read = "embedding::text")
    private String embedding;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "chunk_index")
    private Integer chunkIndex;

    @Column(name = "version", nullable = false)
    private Integer version = 1;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "updated_at")
    private java.time.Instant updatedAt = java.time.Instant.now();

    public DocumentChunk() {}

    public DocumentChunk(UUID id, UUID documentId, Tenant tenant, UUID tenantId, String content, String contentHash, String embedding, Map<String, Object> metadata, Integer chunkIndex, Integer version, Boolean isActive, java.time.Instant updatedAt) {
        this.id = id;
        this.documentId = documentId;
        if (tenant != null) {
            setTenant(tenant);
        } else if (tenantId != null) {
            setTenantId(tenantId);
        }
        this.content = content;
        this.contentHash = contentHash;
        this.embedding = embedding;
        this.metadata = metadata;
        this.chunkIndex = chunkIndex;
        this.version = version != null ? version : 1;
        this.isActive = isActive != null ? isActive : true;
        this.updatedAt = updatedAt != null ? updatedAt : java.time.Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getDocumentId() { return documentId; }
    public void setDocumentId(UUID documentId) { this.documentId = documentId; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public String getEmbedding() { return embedding; }
    public void setEmbedding(String embedding) { this.embedding = embedding; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    public Integer getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(Integer chunkIndex) { this.chunkIndex = chunkIndex; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public java.time.Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(java.time.Instant updatedAt) { this.updatedAt = updatedAt; }

    public static DocumentChunkBuilder builder() {
        return new DocumentChunkBuilder();
    }

    public static class DocumentChunkBuilder {
        private UUID id;
        private UUID documentId;
        private Tenant tenant;
        private UUID tenantId;
        private String content;
        private String contentHash;
        private String embedding;
        private Map<String, Object> metadata;
        private Integer chunkIndex;
        private Integer version = 1;
        private Boolean isActive = true;
        private java.time.Instant updatedAt = java.time.Instant.now();

        public DocumentChunkBuilder id(UUID id) { this.id = id; return this; }
        public DocumentChunkBuilder documentId(UUID documentId) { this.documentId = documentId; return this; }
        public DocumentChunkBuilder tenant(Tenant tenant) { this.tenant = tenant; return this; }
        public DocumentChunkBuilder tenantId(UUID tenantId) { this.tenantId = tenantId; return this; }
        public DocumentChunkBuilder content(String content) { this.content = content; return this; }
        public DocumentChunkBuilder contentHash(String contentHash) { this.contentHash = contentHash; return this; }
        public DocumentChunkBuilder embedding(String embedding) { this.embedding = embedding; return this; }
        public DocumentChunkBuilder metadata(Map<String, Object> metadata) { this.metadata = metadata; return this; }
        public DocumentChunkBuilder chunkIndex(Integer chunkIndex) { this.chunkIndex = chunkIndex; return this; }
        public DocumentChunkBuilder version(Integer version) { this.version = version; return this; }
        public DocumentChunkBuilder isActive(Boolean isActive) { this.isActive = isActive; return this; }
        public DocumentChunkBuilder updatedAt(java.time.Instant updatedAt) { this.updatedAt = updatedAt; return this; }

        public DocumentChunk build() {
            return new DocumentChunk(id, documentId, tenant, tenantId, content, contentHash, embedding, metadata, chunkIndex, version, isActive, updatedAt);
        }
    }
}
