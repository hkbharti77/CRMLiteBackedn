package com.chatcrmlite.backend.dto;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import java.io.Serializable;

public class AppointmentDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private UUID id;
    private String referenceNumber;
    private String contactName;
    private String contactWaId;
    private UUID contactId;
    private LocalDateTime appointmentDateTime;
    private String title;
    private Map<String, String> collectedData;
    private String meetingLink;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String ownerName;

    public AppointmentDTO() {}

    public AppointmentDTO(UUID id, String referenceNumber, String contactName, String contactWaId, UUID contactId, LocalDateTime appointmentDateTime, String title, Map<String, String> collectedData, String meetingLink, String status, LocalDateTime createdAt, LocalDateTime updatedAt, String ownerName) {
        this.id = id;
        this.referenceNumber = referenceNumber;
        this.contactName = contactName;
        this.contactWaId = contactWaId;
        this.contactId = contactId;
        this.appointmentDateTime = appointmentDateTime;
        this.title = title;
        this.collectedData = collectedData;
        this.meetingLink = meetingLink;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.ownerName = ownerName;
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
    public LocalDateTime getAppointmentDateTime() { return appointmentDateTime; }
    public void setAppointmentDateTime(LocalDateTime appointmentDateTime) { this.appointmentDateTime = appointmentDateTime; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public Map<String, String> getCollectedData() { return collectedData; }
    public void setCollectedData(Map<String, String> collectedData) { this.collectedData = collectedData; }
    public String getMeetingLink() { return meetingLink; }
    public void setMeetingLink(String meetingLink) { this.meetingLink = meetingLink; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }

    public static AppointmentDTOBuilder builder() {
        return new AppointmentDTOBuilder();
    }

    public static class AppointmentDTOBuilder {
        private UUID id;
        private String referenceNumber;
        private String contactName;
        private String contactWaId;
        private UUID contactId;
        private LocalDateTime appointmentDateTime;
        private String title;
        private Map<String, String> collectedData;
        private String meetingLink;
        private String status;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private String ownerName;

        public AppointmentDTOBuilder id(UUID id) { this.id = id; return this; }
        public AppointmentDTOBuilder referenceNumber(String referenceNumber) { this.referenceNumber = referenceNumber; return this; }
        public AppointmentDTOBuilder contactName(String contactName) { this.contactName = contactName; return this; }
        public AppointmentDTOBuilder contactWaId(String contactWaId) { this.contactWaId = contactWaId; return this; }
        public AppointmentDTOBuilder contactId(UUID contactId) { this.contactId = contactId; return this; }
        public AppointmentDTOBuilder appointmentDateTime(LocalDateTime appointmentDateTime) { this.appointmentDateTime = appointmentDateTime; return this; }
        public AppointmentDTOBuilder title(String title) { this.title = title; return this; }
        public AppointmentDTOBuilder collectedData(Map<String, String> collectedData) { this.collectedData = collectedData; return this; }
        public AppointmentDTOBuilder meetingLink(String meetingLink) { this.meetingLink = meetingLink; return this; }
        public AppointmentDTOBuilder status(String status) { this.status = status; return this; }
        public AppointmentDTOBuilder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public AppointmentDTOBuilder updatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; return this; }
        public AppointmentDTOBuilder ownerName(String ownerName) { this.ownerName = ownerName; return this; }

        public AppointmentDTO build() {
            return new AppointmentDTO(id, referenceNumber, contactName, contactWaId, contactId, appointmentDateTime, title, collectedData, meetingLink, status, createdAt, updatedAt, ownerName);
        }
    }
}
