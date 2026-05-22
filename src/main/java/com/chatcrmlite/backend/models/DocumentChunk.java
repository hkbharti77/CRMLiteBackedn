package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "document_chunks")
public class DocumentChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", referencedColumnName = "id", insertable = false, updatable = false)
    private User tenant;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

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

    public DocumentChunk() {}

    public DocumentChunk(UUID id, UUID documentId, User tenant, UUID tenantId, String content, String contentHash, String embedding, Map<String, Object> metadata) {
        this.id = id;
        this.documentId = documentId;
        this.tenant = tenant;
        this.tenantId = tenantId;
        this.content = content;
        this.contentHash = contentHash;
        this.embedding = embedding;
        this.metadata = metadata;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getDocumentId() { return documentId; }
    public void setDocumentId(UUID documentId) { this.documentId = documentId; }
    public User getTenant() { return tenant; }
    public void setTenant(User tenant) { this.tenant = tenant; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public String getEmbedding() { return embedding; }
    public void setEmbedding(String embedding) { this.embedding = embedding; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    public static DocumentChunkBuilder builder() {
        return new DocumentChunkBuilder();
    }

    public static class DocumentChunkBuilder {
        private UUID id;
        private UUID documentId;
        private User tenant;
        private UUID tenantId;
        private String content;
        private String contentHash;
        private String embedding;
        private Map<String, Object> metadata;

        public DocumentChunkBuilder id(UUID id) { this.id = id; return this; }
        public DocumentChunkBuilder documentId(UUID documentId) { this.documentId = documentId; return this; }
        public DocumentChunkBuilder tenant(User tenant) { this.tenant = tenant; return this; }
        public DocumentChunkBuilder tenantId(UUID tenantId) { this.tenantId = tenantId; return this; }
        public DocumentChunkBuilder content(String content) { this.content = content; return this; }
        public DocumentChunkBuilder contentHash(String contentHash) { this.contentHash = contentHash; return this; }
        public DocumentChunkBuilder embedding(String embedding) { this.embedding = embedding; return this; }
        public DocumentChunkBuilder metadata(Map<String, Object> metadata) { this.metadata = metadata; return this; }

        public DocumentChunk build() {
            return new DocumentChunk(id, documentId, tenant, tenantId, content, contentHash, embedding, metadata);
        }
    }
}
