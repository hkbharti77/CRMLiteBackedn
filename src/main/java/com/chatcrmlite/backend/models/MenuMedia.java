package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "menu_media")
public class MenuMedia {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(name = "image_data", columnDefinition = "BYTEA")
    private byte[] imageData;

    private String contentType;

    public MenuMedia() {}

    public MenuMedia(UUID id, User owner, byte[] imageData, String contentType) {
        this.id = id;
        this.owner = owner;
        this.imageData = imageData;
        this.contentType = contentType;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public User getOwner() { return owner; }
    public void setOwner(User owner) { this.owner = owner; }
    public byte[] getImageData() { return imageData; }
    public void setImageData(byte[] imageData) { this.imageData = imageData; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public static MenuMediaBuilder builder() {
        return new MenuMediaBuilder();
    }

    public static class MenuMediaBuilder {
        private UUID id;
        private User owner;
        private byte[] imageData;
        private String contentType;

        public MenuMediaBuilder id(UUID id) { this.id = id; return this; }
        public MenuMediaBuilder owner(User owner) { this.owner = owner; return this; }
        public MenuMediaBuilder imageData(byte[] imageData) { this.imageData = imageData; return this; }
        public MenuMediaBuilder contentType(String contentType) { this.contentType = contentType; return this; }

        public MenuMedia build() {
            return new MenuMedia(id, owner, imageData, contentType);
        }
    }
}
