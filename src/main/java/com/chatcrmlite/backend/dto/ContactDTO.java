package com.chatcrmlite.backend.dto;

import java.io.Serializable;
import java.util.List;
import java.util.UUID;

public class ContactDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private UUID id;
    private String waId;
    private String name;
    private String email;
    private String phone;
    private List<String> tags;
    private String source;
    private boolean botPaused;
    
    // Channel-specific consent fields
    private String whatsappConsentStatus = "UNKNOWN";
    private String emailConsentStatus = "UNKNOWN";
    private String smsConsentStatus = "UNKNOWN";
    private boolean marketingOptedOut = false;

    public ContactDTO() {}

    public ContactDTO(UUID id, String waId, String name, String email, String phone, List<String> tags, String source, boolean botPaused) {
        this.id = id;
        this.waId = waId;
        this.name = name;
        this.email = email;
        this.phone = phone;
        this.tags = tags;
        this.source = source;
        this.botPaused = botPaused;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getWaId() { return waId; }
    public void setWaId(String waId) { this.waId = waId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public boolean isBotPaused() { return botPaused; }
    public void setBotPaused(boolean botPaused) { this.botPaused = botPaused; }

    public String getWhatsappConsentStatus() { return whatsappConsentStatus; }
    public void setWhatsappConsentStatus(String whatsappConsentStatus) { this.whatsappConsentStatus = whatsappConsentStatus; }
    public String getEmailConsentStatus() { return emailConsentStatus; }
    public void setEmailConsentStatus(String emailConsentStatus) { this.emailConsentStatus = emailConsentStatus; }
    public String getSmsConsentStatus() { return smsConsentStatus; }
    public void setSmsConsentStatus(String smsConsentStatus) { this.smsConsentStatus = smsConsentStatus; }
    public boolean isMarketingOptedOut() { return marketingOptedOut; }
    public void setMarketingOptedOut(boolean marketingOptedOut) { this.marketingOptedOut = marketingOptedOut; }

    public static ContactDTOBuilder builder() {
        return new ContactDTOBuilder();
    }

    public static class ContactDTOBuilder {
        private UUID id;
        private String waId;
        private String name;
        private String email;
        private String phone;
        private List<String> tags;
        private String source;
        private boolean botPaused;
        private String whatsappConsentStatus = "UNKNOWN";
        private String emailConsentStatus = "UNKNOWN";
        private String smsConsentStatus = "UNKNOWN";
        private boolean marketingOptedOut = false;

        public ContactDTOBuilder id(UUID id) { this.id = id; return this; }
        public ContactDTOBuilder waId(String waId) { this.waId = waId; return this; }
        public ContactDTOBuilder name(String name) { this.name = name; return this; }
        public ContactDTOBuilder email(String email) { this.email = email; return this; }
        public ContactDTOBuilder phone(String phone) { this.phone = phone; return this; }
        public ContactDTOBuilder tags(List<String> tags) { this.tags = tags; return this; }
        public ContactDTOBuilder source(String source) { this.source = source; return this; }
        public ContactDTOBuilder botPaused(boolean botPaused) { this.botPaused = botPaused; return this; }
        public ContactDTOBuilder whatsappConsentStatus(String whatsappConsentStatus) { this.whatsappConsentStatus = whatsappConsentStatus; return this; }
        public ContactDTOBuilder emailConsentStatus(String emailConsentStatus) { this.emailConsentStatus = emailConsentStatus; return this; }
        public ContactDTOBuilder smsConsentStatus(String smsConsentStatus) { this.smsConsentStatus = smsConsentStatus; return this; }
        public ContactDTOBuilder marketingOptedOut(boolean marketingOptedOut) { this.marketingOptedOut = marketingOptedOut; return this; }

        public ContactDTO build() {
            ContactDTO dto = new ContactDTO(id, waId, name, email, phone, tags, source, botPaused);
            dto.setWhatsappConsentStatus(whatsappConsentStatus);
            dto.setEmailConsentStatus(emailConsentStatus);
            dto.setSmsConsentStatus(smsConsentStatus);
            dto.setMarketingOptedOut(marketingOptedOut);
            return dto;
        }
    }
}
