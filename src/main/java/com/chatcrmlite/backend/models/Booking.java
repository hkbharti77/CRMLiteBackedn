package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "bookings")
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Human-readable booking reference number.
     * Format: {PREFIX}-{TYPE}-{YYYYMMDD}-{NNNN}
     * Example: GYAN-B-20250520-0001
     * Generated on first save; unique per owner.
     */
    @Column(name = "reference_number", unique = false, updatable = false)
    private String referenceNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contact_id", nullable = false)
    private Contact contact;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(nullable = false)
    private String service;

    private String preferredSlot;

    @Column(name = "collected_data", columnDefinition = "text")
    private String collectedData = "{}";

    @Enumerated(EnumType.STRING)
    private BookingStatus status = BookingStatus.CONFIRMED;

    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt;

    public Booking() {}

    public Booking(UUID id, String referenceNumber, Contact contact, User owner, String service, String preferredSlot, String collectedData, BookingStatus status, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.referenceNumber = referenceNumber;
        this.contact = contact;
        this.owner = owner;
        this.service = service;
        this.preferredSlot = preferredSlot;
        this.collectedData = collectedData != null ? collectedData : "{}";
        this.status = status != null ? status : BookingStatus.CONFIRMED;
        this.createdAt = createdAt != null ? createdAt : LocalDateTime.now();
        this.updatedAt = updatedAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public enum BookingStatus {
        CONFIRMED, COMPLETED, CANCELLED, NO_SHOW
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getReferenceNumber() { return referenceNumber; }
    public void setReferenceNumber(String referenceNumber) { this.referenceNumber = referenceNumber; }
    public Contact getContact() { return contact; }
    public void setContact(Contact contact) { this.contact = contact; }
    public User getOwner() { return owner; }
    public void setOwner(User owner) { this.owner = owner; }
    public String getService() { return service; }
    public void setService(String service) { this.service = service; }
    public String getPreferredSlot() { return preferredSlot; }
    public void setPreferredSlot(String preferredSlot) { this.preferredSlot = preferredSlot; }
    public String getCollectedData() { return collectedData; }
    public void setCollectedData(String collectedData) { this.collectedData = collectedData; }
    public BookingStatus getStatus() { return status; }
    public void setStatus(BookingStatus status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public static BookingBuilder builder() {
        return new BookingBuilder();
    }

    public static class BookingBuilder {
        private UUID id;
        private String referenceNumber;
        private Contact contact;
        private User owner;
        private String service;
        private String preferredSlot;
        private String collectedData = "{}";
        private BookingStatus status = BookingStatus.CONFIRMED;
        private LocalDateTime createdAt = LocalDateTime.now();
        private LocalDateTime updatedAt;

        public BookingBuilder id(UUID id) { this.id = id; return this; }
        public BookingBuilder referenceNumber(String referenceNumber) { this.referenceNumber = referenceNumber; return this; }
        public BookingBuilder contact(Contact contact) { this.contact = contact; return this; }
        public BookingBuilder owner(User owner) { this.owner = owner; return this; }
        public BookingBuilder service(String service) { this.service = service; return this; }
        public BookingBuilder preferredSlot(String preferredSlot) { this.preferredSlot = preferredSlot; return this; }
        public BookingBuilder collectedData(String collectedData) { this.collectedData = collectedData; return this; }
        public BookingBuilder status(BookingStatus status) { this.status = status; return this; }
        public BookingBuilder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public BookingBuilder updatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; return this; }

        public Booking build() {
            return new Booking(id, referenceNumber, contact, owner, service, preferredSlot, collectedData, status, createdAt, updatedAt);
        }
    }
}
