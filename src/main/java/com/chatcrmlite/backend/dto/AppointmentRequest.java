package com.chatcrmlite.backend.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public class AppointmentRequest {
    private UUID contactId;
    private LocalDateTime appointmentDateTime;
    private String title;
    private String meetingLink;

    public AppointmentRequest() {}

    public AppointmentRequest(UUID contactId, LocalDateTime appointmentDateTime, String title, String meetingLink) {
        this.contactId = contactId;
        this.appointmentDateTime = appointmentDateTime;
        this.title = title;
        this.meetingLink = meetingLink;
    }

    public UUID getContactId() { return contactId; }
    public void setContactId(UUID contactId) { this.contactId = contactId; }
    public LocalDateTime getAppointmentDateTime() { return appointmentDateTime; }
    public void setAppointmentDateTime(LocalDateTime appointmentDateTime) { this.appointmentDateTime = appointmentDateTime; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getMeetingLink() { return meetingLink; }
    public void setMeetingLink(String meetingLink) { this.meetingLink = meetingLink; }
}
