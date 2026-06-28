package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;

import java.util.UUID;

@Entity
@Table(name = "appointments")
public class Appointment extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Human-readable appointment reference number.
     * Format: {PREFIX}-{TYPE}-{YYYYMMDD}-{NNNN}
     * Example: GYAN-A-20250520-0001
     * Generated on first save; unique per owner.
     */
    @Column(name = "reference_number", unique = false, updatable = false)
    private String referenceNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contact_id", nullable = true)
    private Contact contact;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(nullable = false)
    private LocalDateTime appointmentDateTime;

    @Column(nullable = false)
    private String title;

    // AP-9: Changed from TEXT to JSONB (column converted in V10031)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "collected_data", columnDefinition = "jsonb")
    private String collectedData = "{}";

    @Column(name = "source", length = 50)
    private String source = "MANUAL";

    private String meetingLink;

    @Enumerated(EnumType.STRING)
    private AppointmentStatus status = AppointmentStatus.SCHEDULED;

    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt;

    public Appointment() {}

    public Appointment(UUID id, String referenceNumber, Contact contact, User owner, LocalDateTime appointmentDateTime, String title, String collectedData, String meetingLink, AppointmentStatus status, String source, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.referenceNumber = referenceNumber;
        this.contact = contact;
        this.owner = owner;
        this.appointmentDateTime = appointmentDateTime;
        this.title = title;
        this.collectedData = collectedData != null ? collectedData : "{}";
        this.meetingLink = meetingLink;
        this.source = source != null ? source : "MANUAL";
        this.status = status != null ? status : AppointmentStatus.SCHEDULED;
        this.createdAt = createdAt != null ? createdAt : LocalDateTime.now();
        this.updatedAt = updatedAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public enum AppointmentStatus {
        SCHEDULED, COMPLETED, CANCELLED, NO_SHOW
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getReferenceNumber() { return referenceNumber; }
    public void setReferenceNumber(String referenceNumber) { this.referenceNumber = referenceNumber; }
    public Contact getContact() { return contact; }
    public void setContact(Contact contact) { this.contact = contact; }
    public User getOwner() { return owner; }
    public void setOwner(User owner) { this.owner = owner; }
    public LocalDateTime getAppointmentDateTime() { return appointmentDateTime; }
    public void setAppointmentDateTime(LocalDateTime appointmentDateTime) { this.appointmentDateTime = appointmentDateTime; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getCollectedData() { return collectedData; }
    public void setCollectedData(String collectedData) { this.collectedData = collectedData; }
    public String getMeetingLink() { return meetingLink; }
    public void setMeetingLink(String meetingLink) { this.meetingLink = meetingLink; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public AppointmentStatus getStatus() { return status; }
    public void setStatus(AppointmentStatus status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public static AppointmentBuilder builder() {
        return new AppointmentBuilder();
    }

    public static class AppointmentBuilder {
        private UUID id;
        private String referenceNumber;
        private Contact contact;
        private User owner;
        private LocalDateTime appointmentDateTime;
        private String title;
        private String collectedData = "{}";
        private String meetingLink;
        private String source = "MANUAL";
        private AppointmentStatus status = AppointmentStatus.SCHEDULED;
        private LocalDateTime createdAt = LocalDateTime.now();
        private LocalDateTime updatedAt;

        public AppointmentBuilder id(UUID id) { this.id = id; return this; }
        public AppointmentBuilder referenceNumber(String referenceNumber) { this.referenceNumber = referenceNumber; return this; }
        public AppointmentBuilder contact(Contact contact) { this.contact = contact; return this; }
        public AppointmentBuilder owner(User owner) { this.owner = owner; return this; }
        public AppointmentBuilder appointmentDateTime(LocalDateTime appointmentDateTime) { this.appointmentDateTime = appointmentDateTime; return this; }
        public AppointmentBuilder title(String title) { this.title = title; return this; }
        public AppointmentBuilder collectedData(String collectedData) { this.collectedData = collectedData; return this; }
        public AppointmentBuilder meetingLink(String meetingLink) { this.meetingLink = meetingLink; return this; }
        public AppointmentBuilder source(String source) { this.source = source; return this; }
        public AppointmentBuilder status(AppointmentStatus status) { this.status = status; return this; }
        public AppointmentBuilder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public AppointmentBuilder updatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; return this; }

        public Appointment build() {
            return new Appointment(id, referenceNumber, contact, owner, appointmentDateTime, title, collectedData, meetingLink, status, source, createdAt, updatedAt);
        }
    }
}
