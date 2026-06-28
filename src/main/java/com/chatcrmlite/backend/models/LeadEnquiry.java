package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Relational replacement for Lead.enquiries JSON blob (AP-1 fix).
 *
 * Each row is one enquiry attached to a Lead. FK constraint guarantees
 * referential integrity. The old JSON TEXT column is preserved as a
 * deprecated field during migration, then dropped in a later migration.
 *
 * Concurrent writes are now safe: two transactions writing to the same lead
 * insert independent rows rather than racing to overwrite a single JSON string.
 */
@Entity
@Table(
    name = "lead_enquiries",
    indexes = {
        @Index(name = "idx_le_lead_id",      columnList = "lead_id"),
        @Index(name = "idx_le_lead_created", columnList = "lead_id, created_at DESC")
    }
)
public class LeadEnquiry implements Serializable {
    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lead_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_lead_enquiry_lead"))
    private Lead lead;

    /**
     * Enquiry type: MANUAL | WHATSAPP | FORM | AUTO
     */
    @Column(name = "type", nullable = false, length = 50)
    private String type = "MANUAL";

    @Column(name = "message", columnDefinition = "TEXT", nullable = false)
    private String message;

    /**
     * Source context: "Manual Entry" | "WhatsApp" | "Support Form" etc.
     */
    @Column(name = "source", length = 255)
    private String source = "Manual Entry";

    /**
     * Resolution state: OPEN | IN_PROGRESS | RESOLVED | CLOSED
     */
    @Column(name = "status", length = 30, nullable = false)
    private String status = "OPEN";

    @Column(name = "name", length = 255)
    private String name;

    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "phone", length = 50)
    private String phone;

    @Column(name = "company", length = 255)
    private String company;

    @Column(name = "service_category", length = 255)
    private String serviceCategory;

    @Column(name = "requirement", columnDefinition = "TEXT")
    private String requirement;

    @Column(name = "budget", length = 255)
    private String budget;

    @Column(name = "city", length = 255)
    private String city;

    @Column(name = "country", length = 255)
    private String country;

    @Column(name = "age", length = 255)
    private String age;

    @Column(name = "gender", length = 255)
    private String gender;

    @Column(name = "address", columnDefinition = "TEXT")
    private String address;

    @Column(name = "pincode", length = 255)
    private String pincode;

    @Column(name = "preferred_date", length = 255)
    private String preferredDate;

    @Column(name = "additional_details", columnDefinition = "TEXT")
    private String additionalDetails;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public LeadEnquiry() {}

    public LeadEnquiry(Lead lead, String type, String message, String source, String status) {
        this.lead = lead;
        this.type = type != null ? type : "MANUAL";
        this.message = message;
        this.source = source != null ? source : "Manual Entry";
        this.status = status != null ? status : "OPEN";
    }

    // ── Getters / Setters ────────────────────────────────────────────────

    public UUID getId()                      { return id; }
    public Lead getLead()                    { return lead; }
    public String getType()                  { return type; }
    public String getMessage()               { return message; }
    public String getSource()                { return source; }
    public String getStatus()                { return status; }
    public String getName()                  { return name; }
    public String getEmail()                 { return email; }
    public String getPhone()                 { return phone; }
    public String getCompany()               { return company; }
    public String getServiceCategory()       { return serviceCategory; }
    public String getRequirement()           { return requirement; }
    public String getBudget()                { return budget; }
    public String getCity()                  { return city; }
    public String getCountry()               { return country; }
    public String getAge()                   { return age; }
    public String getGender()                { return gender; }
    public String getAddress()               { return address; }
    public String getPincode()               { return pincode; }
    public String getPreferredDate()         { return preferredDate; }
    public String getAdditionalDetails()     { return additionalDetails; }
    public LocalDateTime getCreatedAt()      { return createdAt; }
    public LocalDateTime getUpdatedAt()      { return updatedAt; }

    public void setId(UUID id)               { this.id = id; }
    public void setLead(Lead lead)           { this.lead = lead; }
    public void setType(String type)         { this.type = type; }
    public void setMessage(String message)   { this.message = message; }
    public void setSource(String source)     { this.source = source; }
    public void setStatus(String status)     { this.status = status; }
    public void setName(String name)         { this.name = name; }
    public void setEmail(String email)       { this.email = email; }
    public void setPhone(String phone)       { this.phone = phone; }
    public void setCompany(String company)   { this.company = company; }
    public void setServiceCategory(String serviceCategory) { this.serviceCategory = serviceCategory; }
    public void setRequirement(String requirement) { this.requirement = requirement; }
    public void setBudget(String budget)     { this.budget = budget; }
    public void setCity(String city)         { this.city = city; }
    public void setCountry(String country)   { this.country = country; }
    public void setAge(String age)           { this.age = age; }
    public void setGender(String gender)     { this.gender = gender; }
    public void setAddress(String address)   { this.address = address; }
    public void setPincode(String pincode)   { this.pincode = pincode; }
    public void setPreferredDate(String pd)  { this.preferredDate = pd; }
    public void setAdditionalDetails(String ad){ this.additionalDetails = ad; }
    public void setCreatedAt(LocalDateTime t){ this.createdAt = t; }
    public void setUpdatedAt(LocalDateTime t){ this.updatedAt = t; }

    // ── Builder ──────────────────────────────────────────────────────────

    public static LeadEnquiryBuilder builder() { return new LeadEnquiryBuilder(); }

    public static class LeadEnquiryBuilder {
        private Lead lead;
        private String type = "MANUAL";
        private String message;
        private String source = "Manual Entry";
        private String status = "OPEN";
        private String name;
        private String email;
        private String phone;
        private String company;
        private String serviceCategory;
        private String requirement;
        private String budget;
        private String city;
        private String country;
        private String age;
        private String gender;
        private String address;
        private String pincode;
        private String preferredDate;
        private String additionalDetails;

        public LeadEnquiryBuilder lead(Lead lead)       { this.lead = lead;     return this; }
        public LeadEnquiryBuilder type(String type)     { this.type = type;     return this; }
        public LeadEnquiryBuilder message(String msg)   { this.message = msg;   return this; }
        public LeadEnquiryBuilder source(String src)    { this.source = src;    return this; }
        public LeadEnquiryBuilder status(String status) { this.status = status; return this; }
        public LeadEnquiryBuilder name(String name)     { this.name = name;     return this; }
        public LeadEnquiryBuilder email(String email)   { this.email = email;   return this; }
        public LeadEnquiryBuilder phone(String phone)   { this.phone = phone;   return this; }
        public LeadEnquiryBuilder company(String company){ this.company = company; return this; }
        public LeadEnquiryBuilder serviceCategory(String serviceCategory) { this.serviceCategory = serviceCategory; return this; }
        public LeadEnquiryBuilder requirement(String requirement) { this.requirement = requirement; return this; }
        public LeadEnquiryBuilder budget(String budget) { this.budget = budget; return this; }
        public LeadEnquiryBuilder city(String city)     { this.city = city;     return this; }
        public LeadEnquiryBuilder country(String country){ this.country = country; return this; }
        public LeadEnquiryBuilder age(String age)       { this.age = age;       return this; }
        public LeadEnquiryBuilder gender(String gender) { this.gender = gender; return this; }
        public LeadEnquiryBuilder address(String address){ this.address = address; return this; }
        public LeadEnquiryBuilder pincode(String pincode){ this.pincode = pincode; return this; }
        public LeadEnquiryBuilder preferredDate(String pd) { this.preferredDate = pd; return this; }
        public LeadEnquiryBuilder additionalDetails(String ad) { this.additionalDetails = ad; return this; }

        public LeadEnquiry build() {
            LeadEnquiry enquiry = new LeadEnquiry(lead, type, message, source, status);
            enquiry.setName(name);
            enquiry.setEmail(email);
            enquiry.setPhone(phone);
            enquiry.setCompany(company);
            enquiry.setServiceCategory(serviceCategory);
            enquiry.setRequirement(requirement);
            enquiry.setBudget(budget);
            enquiry.setCity(city);
            enquiry.setCountry(country);
            enquiry.setAge(age);
            enquiry.setGender(gender);
            enquiry.setAddress(address);
            enquiry.setPincode(pincode);
            enquiry.setPreferredDate(preferredDate);
            enquiry.setAdditionalDetails(additionalDetails);
            return enquiry;
        }
    }
}
