package com.chatcrmlite.backend.dto;

import com.chatcrmlite.backend.models.Lead;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import java.io.Serializable;

public class LeadDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private UUID id;
    private String leadNumber;   // e.g. "GYAN-0001"
    private ContactDTO contact;
    private Lead.LeadStatus status;
    private List<EnquiryDTO> enquiries;
    private LocalDateTime createdAt;
    private LocalDateTime lastActivity;
    private BigDecimal dealValue;
    private Lead.PaymentStatus paymentStatus;
    private String currency;
    private String dealLabel;
    private boolean isNew;
    private String createdAtHuman;
    private String ownerName;

    public LeadDTO() {}

    public LeadDTO(UUID id, String leadNumber, ContactDTO contact, Lead.LeadStatus status, List<EnquiryDTO> enquiries, LocalDateTime createdAt, LocalDateTime lastActivity, BigDecimal dealValue, Lead.PaymentStatus paymentStatus, String currency, String dealLabel, boolean isNew, String createdAtHuman, String ownerName) {
        this.id = id;
        this.leadNumber = leadNumber;
        this.contact = contact;
        this.status = status;
        this.enquiries = enquiries;
        this.createdAt = createdAt;
        this.lastActivity = lastActivity;
        this.dealValue = dealValue;
        this.paymentStatus = paymentStatus;
        this.currency = currency;
        this.dealLabel = dealLabel;
        this.isNew = isNew;
        this.createdAtHuman = createdAtHuman;
        this.ownerName = ownerName;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getLeadNumber() { return leadNumber; }
    public void setLeadNumber(String leadNumber) { this.leadNumber = leadNumber; }
    public ContactDTO getContact() { return contact; }
    public void setContact(ContactDTO contact) { this.contact = contact; }
    public Lead.LeadStatus getStatus() { return status; }
    public void setStatus(Lead.LeadStatus status) { this.status = status; }
    public List<EnquiryDTO> getEnquiries() { return enquiries; }
    public void setEnquiries(List<EnquiryDTO> enquiries) { this.enquiries = enquiries; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getLastActivity() { return lastActivity; }
    public void setLastActivity(LocalDateTime lastActivity) { this.lastActivity = lastActivity; }
    public BigDecimal getDealValue() { return dealValue; }
    public void setDealValue(BigDecimal dealValue) { this.dealValue = dealValue; }
    public Lead.PaymentStatus getPaymentStatus() { return paymentStatus; }
    public void setPaymentStatus(Lead.PaymentStatus paymentStatus) { this.paymentStatus = paymentStatus; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getDealLabel() { return dealLabel; }
    public void setDealLabel(String dealLabel) { this.dealLabel = dealLabel; }
    public boolean isNew() { return isNew; }
    public void setNew(boolean isNew) { this.isNew = isNew; }
    public String getCreatedAtHuman() { return createdAtHuman; }
    public void setCreatedAtHuman(String createdAtHuman) { this.createdAtHuman = createdAtHuman; }
    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }

    public static LeadDTOBuilder builder() {
        return new LeadDTOBuilder();
    }

    public static class LeadDTOBuilder {
        private UUID id;
        private String leadNumber;
        private ContactDTO contact;
        private Lead.LeadStatus status;
        private List<EnquiryDTO> enquiries;
        private LocalDateTime createdAt;
        private LocalDateTime lastActivity;
        private BigDecimal dealValue;
        private Lead.PaymentStatus paymentStatus;
        private String currency;
        private String dealLabel;
        private boolean isNew;
        private String createdAtHuman;
        private String ownerName;

        public LeadDTOBuilder id(UUID id) { this.id = id; return this; }
        public LeadDTOBuilder leadNumber(String leadNumber) { this.leadNumber = leadNumber; return this; }
        public LeadDTOBuilder contact(ContactDTO contact) { this.contact = contact; return this; }
        public LeadDTOBuilder status(Lead.LeadStatus status) { this.status = status; return this; }
        public LeadDTOBuilder enquiries(List<EnquiryDTO> enquiries) { this.enquiries = enquiries; return this; }
        public LeadDTOBuilder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public LeadDTOBuilder lastActivity(LocalDateTime lastActivity) { this.lastActivity = lastActivity; return this; }
        public LeadDTOBuilder dealValue(BigDecimal dealValue) { this.dealValue = dealValue; return this; }
        public LeadDTOBuilder paymentStatus(Lead.PaymentStatus paymentStatus) { this.paymentStatus = paymentStatus; return this; }
        public LeadDTOBuilder currency(String currency) { this.currency = currency; return this; }
        public LeadDTOBuilder dealLabel(String dealLabel) { this.dealLabel = dealLabel; return this; }
        public LeadDTOBuilder isNew(boolean isNew) { this.isNew = isNew; return this; }
        public LeadDTOBuilder createdAtHuman(String createdAtHuman) { this.createdAtHuman = createdAtHuman; return this; }
        public LeadDTOBuilder ownerName(String ownerName) { this.ownerName = ownerName; return this; }

        public LeadDTO build() {
            return new LeadDTO(id, leadNumber, contact, status, enquiries, createdAt, lastActivity, dealValue, paymentStatus, currency, dealLabel, isNew, createdAtHuman, ownerName);
        }
    }
}
