package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;


@Entity
@Table(name = "leads", indexes = {
    @Index(name = "idx_lead_contact", columnList = "contact_id")
})
public class Lead extends BaseTenantEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    /**
     * Human-readable lead reference number.
     * Format: first 4 letters of business name (uppercase) + hyphen + 4-digit sequence.
     * Example: GYAN-0001, GYAN-0002, DENT-0001
     * Generated on first save; unique per owner.
     */
    @Column(name = "lead_number", unique = false, updatable = false)
    private String leadNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contact_id", nullable = false)
    private Contact contact;

    @Enumerated(EnumType.STRING)
    private LeadStatus status;

    /**
     * @deprecated AP-1: Migrating to relational lead_enquiries table.
     * This column is preserved during migration for backfill and will be
     * dropped after V10027 data migration completes.
     * Use LeadEnquiryRepository for all new enquiry reads/writes.
     */
    @Deprecated
    @Column(columnDefinition = "text")
    private String enquiries = "[]";

    /** Relational replacement for the enquiries JSON blob (AP-1). */
    @OneToMany(mappedBy = "lead", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @org.hibernate.annotations.BatchSize(size = 20)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private List<LeadEnquiry> enquiryList = new ArrayList<>();

    @Column(name = "deleted", nullable = false, columnDefinition = "BOOLEAN DEFAULT FALSE")
    private boolean deleted = false;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "lead_tags",
        joinColumns = @JoinColumn(name = "lead_id"),
        inverseJoinColumns = @JoinColumn(name = "tag_id")
    )
    @com.fasterxml.jackson.annotation.JsonIgnore
    private List<Tag> tags = new ArrayList<>();

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime lastActivity = LocalDateTime.now();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private User owner;

    private BigDecimal dealValue;

    @Enumerated(EnumType.STRING)
    private PaymentStatus paymentStatus = PaymentStatus.NONE;

    private String currency = "INR";
    private String dealLabel;

    @Column(name = "score")
    private Integer score = 0;

    @Column(name = "interest_category")
    private String interestCategory;

    @Column(name = "lost_reason", columnDefinition = "text")
    private String lostReason;

    @Version
    private Long version;

    public enum ScoreGrade {
        HOT, WARM, COLD
    }

    @Enumerated(EnumType.STRING)
    @Column(name = "score_grade")
    private ScoreGrade scoreGrade = ScoreGrade.COLD;

    @Column(name = "last_scored_at")
    private LocalDateTime lastScoredAt;

    public enum PaymentStatus {
        NONE, PENDING, PARTIAL, PAID
    }

    public enum LeadStatus {
        NEW, INTERESTED, FOLLOW_UP, BOOKED, CLOSED_WON, CLOSED_LOST, CONTACTED, QUALIFIED, WON, LOST
    }

    public Lead() {}

    public Lead(UUID id, String leadNumber, Contact contact, LeadStatus status, String enquiries, boolean deleted, List<Tag> tags, LocalDateTime createdAt, LocalDateTime lastActivity, User owner, BigDecimal dealValue, PaymentStatus paymentStatus, String currency, String dealLabel, Long version, String lostReason) {
        this.id = id;
        this.leadNumber = leadNumber;
        this.contact = contact;
        this.status = status;
        this.enquiries = (enquiries != null) ? enquiries : "[]";
        this.deleted = deleted;
        this.tags = (tags != null) ? tags : new ArrayList<>();
        this.createdAt = (createdAt != null) ? createdAt : LocalDateTime.now();
        this.lastActivity = (lastActivity != null) ? lastActivity : LocalDateTime.now();
        this.owner = owner;
        this.dealValue = dealValue;
        this.paymentStatus = (paymentStatus != null) ? paymentStatus : PaymentStatus.NONE;
        this.currency = (currency != null) ? currency : "INR";
        this.dealLabel = dealLabel;
        this.version = version;
        this.lostReason = lostReason;
    }

    public UUID getId() { return id; }
    public String getLeadNumber() { return leadNumber; }
    public Contact getContact() { return contact; }
    public LeadStatus getStatus() { return status; }
    public String getEnquiries() { return enquiries; }
    public boolean isDeleted() { return deleted; }
    @com.fasterxml.jackson.annotation.JsonIgnore
    public List<Tag> getTags() { return tags; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getLastActivity() { return lastActivity; }
    public User getOwner() { return owner; }
    public BigDecimal getDealValue() { return dealValue; }
    public PaymentStatus getPaymentStatus() { return paymentStatus; }
    public String getCurrency() { return currency; }
    public String getDealLabel() { return dealLabel; }
    public Integer getScore() { return score != null ? score : 0; }
    public String getInterestCategory() { return interestCategory; }
    public ScoreGrade getScoreGrade() { return scoreGrade != null ? scoreGrade : ScoreGrade.COLD; }
    public LocalDateTime getLastScoredAt() { return lastScoredAt; }
    public Long getVersion() { return version; }
    public String getLostReason() { return lostReason; }

    public void setId(UUID id) { this.id = id; }
    public void setLeadNumber(String leadNumber) { this.leadNumber = leadNumber; }
    public void setContact(Contact contact) { this.contact = contact; }
    public void setStatus(LeadStatus status) { this.status = status; }
    /** @deprecated Use LeadEnquiryRepository instead */
    @Deprecated public void setEnquiries(String enquiries) { this.enquiries = enquiries; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }
    public void setTags(List<Tag> tags) { this.tags = tags; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public void setLastActivity(LocalDateTime lastActivity) { this.lastActivity = lastActivity; }
    public void setOwner(User owner) { this.owner = owner; }
    public void setDealValue(BigDecimal dealValue) { this.dealValue = dealValue; }
    public void setPaymentStatus(PaymentStatus paymentStatus) { this.paymentStatus = paymentStatus; }
    public void setCurrency(String currency) { this.currency = currency; }
    public void setDealLabel(String dealLabel) { this.dealLabel = dealLabel; }
    public void setScore(Integer score) { this.score = score; }
    public void setInterestCategory(String interestCategory) { this.interestCategory = interestCategory; }
    public void setScoreGrade(ScoreGrade scoreGrade) { this.scoreGrade = scoreGrade; }
    public void setLastScoredAt(LocalDateTime lastScoredAt) { this.lastScoredAt = lastScoredAt; }
    public void setLostReason(String lostReason) { this.lostReason = lostReason; }
    public void setVersion(Long version) { this.version = version; }
    @com.fasterxml.jackson.annotation.JsonIgnore
    public List<LeadEnquiry> getEnquiryList() { return enquiryList; }
    public void setEnquiryList(List<LeadEnquiry> enquiryList) { this.enquiryList = enquiryList; }

    public static LeadBuilder builder() { return new LeadBuilder(); }

    public static class LeadBuilder {
        private UUID id;
        private String leadNumber;
        private Contact contact;
        private LeadStatus status;
        private String enquiries = "[]";
        private boolean deleted = false;
        private List<Tag> tags;
        private LocalDateTime createdAt = LocalDateTime.now();
        private LocalDateTime lastActivity = LocalDateTime.now();
        private User owner;
        private BigDecimal dealValue;
        private PaymentStatus paymentStatus = PaymentStatus.NONE;
        private String currency = "INR";
        private String dealLabel;
        private Integer score = 0;
        private String interestCategory;
        private Long version;
        private String lostReason;

        public LeadBuilder id(UUID id) { this.id = id; return this; }
        public LeadBuilder leadNumber(String leadNumber) { this.leadNumber = leadNumber; return this; }
        public LeadBuilder contact(Contact contact) { this.contact = contact; return this; }
        public LeadBuilder status(LeadStatus status) { this.status = status; return this; }
        public LeadBuilder enquiries(String enquiries) { this.enquiries = enquiries; return this; }
        public LeadBuilder deleted(boolean deleted) { this.deleted = deleted; return this; }
        public LeadBuilder tags(List<Tag> tags) { this.tags = tags; return this; }
        public LeadBuilder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public LeadBuilder lastActivity(LocalDateTime lastActivity) { this.lastActivity = lastActivity; return this; }
        public LeadBuilder owner(User owner) { this.owner = owner; return this; }
        public LeadBuilder dealValue(BigDecimal dealValue) { this.dealValue = dealValue; return this; }
        public LeadBuilder paymentStatus(PaymentStatus paymentStatus) { this.paymentStatus = paymentStatus; return this; }
        public LeadBuilder currency(String currency) { this.currency = currency; return this; }
        public LeadBuilder dealLabel(String dealLabel) { this.dealLabel = dealLabel; return this; }
        public LeadBuilder score(Integer score) { this.score = score; return this; }
        public LeadBuilder interestCategory(String interestCategory) { this.interestCategory = interestCategory; return this; }
        public LeadBuilder version(Long version) { this.version = version; return this; }
        public LeadBuilder lostReason(String lostReason) { this.lostReason = lostReason; return this; }

        public Lead build() {
            Lead lead = new Lead(id, leadNumber, contact, status, enquiries, deleted, tags, createdAt, lastActivity, owner, dealValue, paymentStatus, currency, dealLabel, version, lostReason);
            lead.setScore(this.score);
            lead.setInterestCategory(this.interestCategory);
            return lead;
        }
    }
}
