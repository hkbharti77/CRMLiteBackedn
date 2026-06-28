package com.chatcrmlite.backend.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public class AppointmentRequest {
    private UUID contactId;
    private LocalDateTime appointmentDateTime;
    private String title;
    private String meetingLink;
    private String source = "MANUAL";
    private Boolean generateMeetLink = false;
    private String clientEmail;
    private Integer durationMinutes;

    public AppointmentRequest() {}

    public AppointmentRequest(UUID contactId, LocalDateTime appointmentDateTime, String title, String meetingLink, String source, Boolean generateMeetLink, String clientEmail, Integer durationMinutes) {
        this.contactId = contactId;
        this.appointmentDateTime = appointmentDateTime;
        this.title = title;
        this.meetingLink = meetingLink;
        this.source = source;
        this.generateMeetLink = generateMeetLink;
        this.clientEmail = clientEmail;
        this.durationMinutes = durationMinutes;
    }

    public UUID getContactId() { return contactId; }
    public void setContactId(UUID contactId) { this.contactId = contactId; }
    public LocalDateTime getAppointmentDateTime() { return appointmentDateTime; }
    public void setAppointmentDateTime(LocalDateTime appointmentDateTime) { this.appointmentDateTime = appointmentDateTime; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getMeetingLink() { return meetingLink; }
    public void setMeetingLink(String meetingLink) { this.meetingLink = meetingLink; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public Boolean getGenerateMeetLink() { return generateMeetLink; }
    public void setGenerateMeetLink(Boolean generateMeetLink) { this.generateMeetLink = generateMeetLink; }
    public String getClientEmail() { return clientEmail; }
    public void setClientEmail(String clientEmail) { this.clientEmail = clientEmail; }
    public Integer getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(Integer durationMinutes) { this.durationMinutes = durationMinutes; }
}
