package com.chatcrmlite.backend.dto;

import com.chatcrmlite.backend.models.Ticket;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public class TicketRequest {

    @NotBlank(message = "Subject is required")
    @Size(max = 255, message = "Subject must not exceed 255 characters")
    private String subject;

    @NotBlank(message = "Description is required")
    @Size(max = 5000, message = "Description must not exceed 5000 characters")
    private String description;

    private UUID contactId;

    @Size(max = 255)
    private String submitterName;

    @Email(message = "Invalid email format")
    @Size(max = 255)
    private String submitterEmail;

    @Size(max = 50)
    private String submitterPhone;

    private Ticket.TicketPriority priority;

    @Size(max = 100)
    private String category;

    private UUID assignedToId;

    public TicketRequest() {}

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public UUID getContactId() { return contactId; }
    public void setContactId(UUID contactId) { this.contactId = contactId; }
    public String getSubmitterName() { return submitterName; }
    public void setSubmitterName(String submitterName) { this.submitterName = submitterName; }
    public String getSubmitterEmail() { return submitterEmail; }
    public void setSubmitterEmail(String submitterEmail) { this.submitterEmail = submitterEmail; }
    public String getSubmitterPhone() { return submitterPhone; }
    public void setSubmitterPhone(String submitterPhone) { this.submitterPhone = submitterPhone; }
    public Ticket.TicketPriority getPriority() { return priority; }
    public void setPriority(Ticket.TicketPriority priority) { this.priority = priority; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public UUID getAssignedToId() { return assignedToId; }
    public void setAssignedToId(UUID assignedToId) { this.assignedToId = assignedToId; }
}
