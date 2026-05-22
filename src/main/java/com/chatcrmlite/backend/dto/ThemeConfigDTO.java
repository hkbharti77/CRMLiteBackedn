package com.chatcrmlite.backend.dto;

public class ThemeConfigDTO {
    private String primaryColor;
    private String secondaryColor;
    private String accentColor;
    private String backgroundColor;
    private String fontFamily;
    private String nicheIcon;
    private String businessName;
    private String welcomeMessage;
    private String businessSubType;
    private String logoUrl;

    public ThemeConfigDTO() {}

    public ThemeConfigDTO(String primaryColor, String secondaryColor, String accentColor, String backgroundColor, String fontFamily, String nicheIcon, String businessName, String welcomeMessage, String businessSubType, String logoUrl) {
        this.primaryColor = primaryColor;
        this.secondaryColor = secondaryColor;
        this.accentColor = accentColor;
        this.backgroundColor = backgroundColor;
        this.fontFamily = fontFamily;
        this.nicheIcon = nicheIcon;
        this.businessName = businessName;
        this.welcomeMessage = welcomeMessage;
        this.businessSubType = businessSubType;
        this.logoUrl = logoUrl;
    }

    public String getPrimaryColor() { return primaryColor; }
    public void setPrimaryColor(String primaryColor) { this.primaryColor = primaryColor; }
    public String getSecondaryColor() { return secondaryColor; }
    public void setSecondaryColor(String secondaryColor) { this.secondaryColor = secondaryColor; }
    public String getAccentColor() { return accentColor; }
    public void setAccentColor(String accentColor) { this.accentColor = accentColor; }
    public String getBackgroundColor() { return backgroundColor; }
    public void setBackgroundColor(String backgroundColor) { this.backgroundColor = backgroundColor; }
    public String getFontFamily() { return fontFamily; }
    public void setFontFamily(String fontFamily) { this.fontFamily = fontFamily; }
    public String getNicheIcon() { return nicheIcon; }
    public void setNicheIcon(String nicheIcon) { this.nicheIcon = nicheIcon; }
    public String getBusinessName() { return businessName; }
    public void setBusinessName(String businessName) { this.businessName = businessName; }
    public String getWelcomeMessage() { return welcomeMessage; }
    public void setWelcomeMessage(String welcomeMessage) { this.welcomeMessage = welcomeMessage; }
    public String getBusinessSubType() { return businessSubType; }
    public void setBusinessSubType(String businessSubType) { this.businessSubType = businessSubType; }
    public String getLogoUrl() { return logoUrl; }
    public void setLogoUrl(String logoUrl) { this.logoUrl = logoUrl; }

    public static ThemeConfigDTOBuilder builder() {
        return new ThemeConfigDTOBuilder();
    }

    public static class ThemeConfigDTOBuilder {
        private String primaryColor;
        private String secondaryColor;
        private String accentColor;
        private String backgroundColor;
        private String fontFamily;
        private String nicheIcon;
        private String businessName;
        private String welcomeMessage;
        private String businessSubType;
        private String logoUrl;

        public ThemeConfigDTOBuilder primaryColor(String primaryColor) { this.primaryColor = primaryColor; return this; }
        public ThemeConfigDTOBuilder secondaryColor(String secondaryColor) { this.secondaryColor = secondaryColor; return this; }
        public ThemeConfigDTOBuilder accentColor(String accentColor) { this.accentColor = accentColor; return this; }
        public ThemeConfigDTOBuilder backgroundColor(String backgroundColor) { this.backgroundColor = backgroundColor; return this; }
        public ThemeConfigDTOBuilder fontFamily(String fontFamily) { this.fontFamily = fontFamily; return this; }
        public ThemeConfigDTOBuilder nicheIcon(String nicheIcon) { this.nicheIcon = nicheIcon; return this; }
        public ThemeConfigDTOBuilder businessName(String businessName) { this.businessName = businessName; return this; }
        public ThemeConfigDTOBuilder welcomeMessage(String welcomeMessage) { this.welcomeMessage = welcomeMessage; return this; }
        public ThemeConfigDTOBuilder businessSubType(String businessSubType) { this.businessSubType = businessSubType; return this; }
        public ThemeConfigDTOBuilder logoUrl(String logoUrl) { this.logoUrl = logoUrl; return this; }

        public ThemeConfigDTO build() {
            return new ThemeConfigDTO(primaryColor, secondaryColor, accentColor, backgroundColor, fontFamily, nicheIcon, businessName, welcomeMessage, businessSubType, logoUrl);
        }
    }
}
