package com.chatcrmlite.backend.dto;

import java.io.Serializable;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class EnquiryDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String id;
    private String type;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String message;
    private String source;
    private String status;
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
    private java.util.Map<String, String> additionalDetails;
    private String createdAt;

    public EnquiryDTO() {}

    public EnquiryDTO(String id, String type, String message, String source, String status, String createdAt) {
        this.id = id;
        this.type = type;
        this.message = message;
        this.source = source;
        this.status = status;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getType() { return type; }
    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getMessage() { return message; }
    public String getSource() { return source; }
    public String getStatus() { return status; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public String getCompany() { return company; }
    public String getServiceCategory() { return serviceCategory; }
    public String getRequirement() { return requirement; }
    public String getBudget() { return budget; }
    public String getCity() { return city; }
    public String getCountry() { return country; }
    public String getAge() { return age; }
    public String getGender() { return gender; }
    public String getAddress() { return address; }
    public String getPincode() { return pincode; }
    public String getPreferredDate() { return preferredDate; }
    public java.util.Map<String, String> getAdditionalDetails() { return additionalDetails; }
    public String getCreatedAt() { return createdAt; }

    public void setId(String id) { this.id = id; }
    public void setType(String type) { this.type = type; }
    public void setMessage(String message) { this.message = message; }
    public void setSource(String source) { this.source = source; }
    public void setStatus(String status) { this.status = status; }
    public void setName(String name) { this.name = name; }
    public void setEmail(String email) { this.email = email; }
    public void setPhone(String phone) { this.phone = phone; }
    public void setCompany(String company) { this.company = company; }
    public void setServiceCategory(String serviceCategory) { this.serviceCategory = serviceCategory; }
    public void setRequirement(String requirement) { this.requirement = requirement; }
    public void setBudget(String budget) { this.budget = budget; }
    public void setCity(String city) { this.city = city; }
    public void setCountry(String country) { this.country = country; }
    public void setAge(String age) { this.age = age; }
    public void setGender(String gender) { this.gender = gender; }
    public void setAddress(String address) { this.address = address; }
    public void setPincode(String pincode) { this.pincode = pincode; }
    public void setPreferredDate(String preferredDate) { this.preferredDate = preferredDate; }
    public void setAdditionalDetails(java.util.Map<String, String> ad) { this.additionalDetails = ad; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public static EnquiryDTOBuilder builder() { return new EnquiryDTOBuilder(); }

    public static class EnquiryDTOBuilder {
        private String id;
        private String type;
        private String message;
        private String source;
        private String status;
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
        private java.util.Map<String, String> additionalDetails;
        private String createdAt;

        public EnquiryDTOBuilder id(String id) { this.id = id; return this; }
        public EnquiryDTOBuilder type(String type) { this.type = type; return this; }
        public EnquiryDTOBuilder message(String message) { this.message = message; return this; }
        public EnquiryDTOBuilder source(String source) { this.source = source; return this; }
        public EnquiryDTOBuilder status(String status) { this.status = status; return this; }
        public EnquiryDTOBuilder name(String name) { this.name = name; return this; }
        public EnquiryDTOBuilder email(String email) { this.email = email; return this; }
        public EnquiryDTOBuilder phone(String phone) { this.phone = phone; return this; }
        public EnquiryDTOBuilder company(String company) { this.company = company; return this; }
        public EnquiryDTOBuilder serviceCategory(String serviceCategory) { this.serviceCategory = serviceCategory; return this; }
        public EnquiryDTOBuilder requirement(String requirement) { this.requirement = requirement; return this; }
        public EnquiryDTOBuilder budget(String budget) { this.budget = budget; return this; }
        public EnquiryDTOBuilder city(String city) { this.city = city; return this; }
        public EnquiryDTOBuilder country(String country) { this.country = country; return this; }
        public EnquiryDTOBuilder age(String age) { this.age = age; return this; }
        public EnquiryDTOBuilder gender(String gender) { this.gender = gender; return this; }
        public EnquiryDTOBuilder address(String address) { this.address = address; return this; }
        public EnquiryDTOBuilder pincode(String pincode) { this.pincode = pincode; return this; }
        public EnquiryDTOBuilder preferredDate(String preferredDate) { this.preferredDate = preferredDate; return this; }
        public EnquiryDTOBuilder additionalDetails(java.util.Map<String, String> ad) { this.additionalDetails = ad; return this; }
        public EnquiryDTOBuilder createdAt(String createdAt) { this.createdAt = createdAt; return this; }

        public EnquiryDTO build() {
            EnquiryDTO dto = new EnquiryDTO(id, type, message, source, status, createdAt);
            dto.setName(name);
            dto.setEmail(email);
            dto.setPhone(phone);
            dto.setCompany(company);
            dto.setServiceCategory(serviceCategory);
            dto.setRequirement(requirement);
            dto.setBudget(budget);
            dto.setCity(city);
            dto.setCountry(country);
            dto.setAge(age);
            dto.setGender(gender);
            dto.setAddress(address);
            dto.setPincode(pincode);
            dto.setPreferredDate(preferredDate);
            dto.setAdditionalDetails(additionalDetails);
            return dto;
        }
    }
}
