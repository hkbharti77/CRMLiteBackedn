package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "business_services")
public class BusinessService extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private User owner;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "image_data", columnDefinition="BYTEA")
    private byte[] imageData;
    
    @Column(name = "image_content_type")
    private String imageContentType;

    @Column(name = "image_url")
    private String imageUrl;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    public BusinessService() {}

    public BusinessService(UUID id, User owner, String name, String description, byte[] imageData, String imageContentType, String imageUrl, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.owner = owner;
        this.name = name;
        this.description = description;
        this.imageData = imageData;
        this.imageContentType = imageContentType;
        this.imageUrl = imageUrl;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public User getOwner() { return owner; }
    public void setOwner(User owner) { this.owner = owner; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public byte[] getImageData() { return imageData; }
    public void setImageData(byte[] imageData) { this.imageData = imageData; }
    public String getImageContentType() { return imageContentType; }
    public void setImageContentType(String imageContentType) { this.imageContentType = imageContentType; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public static BusinessServiceBuilder builder() {
        return new BusinessServiceBuilder();
    }

    public static class BusinessServiceBuilder {
        private UUID id;
        private User owner;
        private String name;
        private String description;
        private byte[] imageData;
        private String imageContentType;
        private String imageUrl;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        public BusinessServiceBuilder id(UUID id) { this.id = id; return this; }
        public BusinessServiceBuilder owner(User owner) { this.owner = owner; return this; }
        public BusinessServiceBuilder name(String name) { this.name = name; return this; }
        public BusinessServiceBuilder description(String description) { this.description = description; return this; }
        public BusinessServiceBuilder imageData(byte[] imageData) { this.imageData = imageData; return this; }
        public BusinessServiceBuilder imageContentType(String imageContentType) { this.imageContentType = imageContentType; return this; }
        public BusinessServiceBuilder imageUrl(String imageUrl) { this.imageUrl = imageUrl; return this; }
        public BusinessServiceBuilder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public BusinessServiceBuilder updatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; return this; }

        public BusinessService build() {
            return new BusinessService(id, owner, name, description, imageData, imageContentType, imageUrl, createdAt, updatedAt);
        }
    }
}
