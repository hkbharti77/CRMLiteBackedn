package com.chatcrmlite.backend.dto;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

public class BookingDTO {
    private UUID id;
    private String referenceNumber;
    private String contactName;
    private String contactWaId;
    private UUID contactId;
    private String service;
    private String preferredSlot;
    private Map<String, String> collectedData;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public BookingDTO() {}

    public BookingDTO(UUID id, String referenceNumber, String contactName, String contactWaId, UUID contactId, String service, String preferredSlot, Map<String, String> collectedData, String status, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.referenceNumber = referenceNumber;
        this.contactName = contactName;
        this.contactWaId = contactWaId;
        this.contactId = contactId;
        this.service = service;
        this.preferredSlot = preferredSlot;
        this.collectedData = collectedData;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getReferenceNumber() { return referenceNumber; }
    public void setReferenceNumber(String referenceNumber) { this.referenceNumber = referenceNumber; }
    public String getContactName() { return contactName; }
    public void setContactName(String contactName) { this.contactName = contactName; }
    public String getContactWaId() { return contactWaId; }
    public void setContactWaId(String contactWaId) { this.contactWaId = contactWaId; }
    public UUID getContactId() { return contactId; }
    public void setContactId(UUID contactId) { this.contactId = contactId; }
    public String getService() { return service; }
    public void setService(String service) { this.service = service; }
    public String getPreferredSlot() { return preferredSlot; }
    public void setPreferredSlot(String preferredSlot) { this.preferredSlot = preferredSlot; }
    public Map<String, String> getCollectedData() { return collectedData; }
    public void setCollectedData(Map<String, String> collectedData) { this.collectedData = collectedData; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public static BookingDTOBuilder builder() {
        return new BookingDTOBuilder();
    }

    public static class BookingDTOBuilder {
        private UUID id;
        private String referenceNumber;
        private String contactName;
        private String contactWaId;
        private UUID contactId;
        private String service;
        private String preferredSlot;
        private Map<String, String> collectedData;
        private String status;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        public BookingDTOBuilder id(UUID id) { this.id = id; return this; }
        public BookingDTOBuilder referenceNumber(String referenceNumber) { this.referenceNumber = referenceNumber; return this; }
        public BookingDTOBuilder contactName(String contactName) { this.contactName = contactName; return this; }
        public BookingDTOBuilder contactWaId(String contactWaId) { this.contactWaId = contactWaId; return this; }
        public BookingDTOBuilder contactId(UUID contactId) { this.contactId = contactId; return this; }
        public BookingDTOBuilder service(String service) { this.service = service; return this; }
        public BookingDTOBuilder preferredSlot(String preferredSlot) { this.preferredSlot = preferredSlot; return this; }
        public BookingDTOBuilder collectedData(Map<String, String> collectedData) { this.collectedData = collectedData; return this; }
        public BookingDTOBuilder status(String status) { this.status = status; return this; }
        public BookingDTOBuilder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public BookingDTOBuilder updatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; return this; }

        public BookingDTO build() {
            return new BookingDTO(id, referenceNumber, contactName, contactWaId, contactId, service, preferredSlot, collectedData, status, createdAt, updatedAt);
        }
    }
}
