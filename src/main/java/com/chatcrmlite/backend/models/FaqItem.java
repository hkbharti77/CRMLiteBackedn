package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "faq_items")
public class FaqItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String question;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String answer;

    @Column(length = 100)
    private String category = "General";

    @Column(columnDefinition = "TEXT")
    private String keywords;

    @Column(name = "embedding", columnDefinition = "vector")
    @org.hibernate.annotations.ColumnTransformer(write = "?::vector", read = "embedding::text")
    private String embedding;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "hit_count", nullable = false)
    private Long hitCount = 0L;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    public FaqItem() {}

    public FaqItem(UUID id, UUID tenantId, String question, String answer, String category, String keywords, String embedding, Boolean isActive, Long hitCount, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.question = question;
        this.answer = answer;
        this.category = category != null ? category : "General";
        this.keywords = keywords;
        this.embedding = embedding;
        this.isActive = isActive != null ? isActive : true;
        this.hitCount = hitCount != null ? hitCount : 0L;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    @PrePersist
    protected void onCreate() {
        this.updatedAt = OffsetDateTime.now();
        if (this.hitCount == null) this.hitCount = 0L;
        if (this.isActive == null) this.isActive = true;
        if (this.category == null) this.category = "General";
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }

    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getKeywords() { return keywords; }
    public void setKeywords(String keywords) { this.keywords = keywords; }

    public String getEmbedding() { return embedding; }
    public void setEmbedding(String embedding) { this.embedding = embedding; }

    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }

    public Long getHitCount() { return hitCount; }
    public void setHitCount(Long hitCount) { this.hitCount = hitCount; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
