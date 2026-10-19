package com.chatcrmlite.backend.dto;

import java.util.List;

public class ThemeConfigDTO {
    private String primaryColor;
    private String secondaryColor;
    private String accentColor;
    private String backgroundColor;
    private String fontFamily;
    private String nicheIcon;
    private String businessName;
    private String welcomeMessage;
    private String returningMessage;
    private String businessSubType;
    private String logoUrl;
    private String widgetIconUrl;
    private Boolean showWatermark;
    private List<WidgetCtaDTO> ctaButtons;
    private List<MenuSectionDTO> menuSections;
    private String aboutUs;
    private String aiResponseMenuJson;
    private String flowCancelMenuJson;
    private String flowCompletionMenuJson;
    private String guardrailMessageAbuse;
    private String guardrailMessageGibberish;
    private String webFlowsRoutingConfigJson;
    private String privacyPolicyUrl;
    private String termsUrl;
    private String grievanceEmail;
    private String consentNoticeText;
    private String address;
    private Double latitude;
    private Double longitude;
    private String phone;
    private String email;
    private String googleMapsUrl;

    public ThemeConfigDTO() {}

    public ThemeConfigDTO(String primaryColor, String secondaryColor, String accentColor, String backgroundColor, String fontFamily, String nicheIcon, String businessName, String welcomeMessage, String returningMessage, String businessSubType, String logoUrl, Boolean showWatermark, List<WidgetCtaDTO> ctaButtons, List<MenuSectionDTO> menuSections, String aboutUs, String aiResponseMenuJson, String flowCancelMenuJson, String flowCompletionMenuJson) {
        this.primaryColor = primaryColor;
        this.secondaryColor = secondaryColor;
        this.accentColor = accentColor;
        this.backgroundColor = backgroundColor;
        this.fontFamily = fontFamily;
        this.nicheIcon = nicheIcon;
        this.businessName = businessName;
        this.welcomeMessage = welcomeMessage;
        this.returningMessage = returningMessage;
        this.businessSubType = businessSubType;
        this.logoUrl = logoUrl;
        this.showWatermark = showWatermark;
        this.ctaButtons = ctaButtons;
        this.menuSections = menuSections;
        this.aboutUs = aboutUs;
        this.aiResponseMenuJson = aiResponseMenuJson;
        this.flowCancelMenuJson = flowCancelMenuJson;
        this.flowCompletionMenuJson = flowCompletionMenuJson;
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
    public String getReturningMessage() { return returningMessage; }
    public void setReturningMessage(String returningMessage) { this.returningMessage = returningMessage; }
    public String getBusinessSubType() { return businessSubType; }
    public void setBusinessSubType(String businessSubType) { this.businessSubType = businessSubType; }
    public String getLogoUrl() { return logoUrl; }
    public void setLogoUrl(String logoUrl) { this.logoUrl = logoUrl; }
    public String getWidgetIconUrl() { return widgetIconUrl; }
    public void setWidgetIconUrl(String widgetIconUrl) { this.widgetIconUrl = widgetIconUrl; }
    public Boolean getShowWatermark() { return showWatermark; }
    public void setShowWatermark(Boolean showWatermark) { this.showWatermark = showWatermark; }
    public List<WidgetCtaDTO> getCtaButtons() { return ctaButtons; }
    public void setCtaButtons(List<WidgetCtaDTO> ctaButtons) { this.ctaButtons = ctaButtons; }
    public List<MenuSectionDTO> getMenuSections() { return menuSections; }
    public void setMenuSections(List<MenuSectionDTO> menuSections) { this.menuSections = menuSections; }
    public String getAboutUs() { return aboutUs; }
    public void setAboutUs(String aboutUs) { this.aboutUs = aboutUs; }
    public String getAiResponseMenuJson() { return aiResponseMenuJson; }
    public void setAiResponseMenuJson(String aiResponseMenuJson) { this.aiResponseMenuJson = aiResponseMenuJson; }
    public String getFlowCancelMenuJson() { return flowCancelMenuJson; }
    public void setFlowCancelMenuJson(String flowCancelMenuJson) { this.flowCancelMenuJson = flowCancelMenuJson; }
    public String getFlowCompletionMenuJson() { return flowCompletionMenuJson; }
    public void setFlowCompletionMenuJson(String flowCompletionMenuJson) { this.flowCompletionMenuJson = flowCompletionMenuJson; }
    public String getGuardrailMessageAbuse() { return guardrailMessageAbuse; }
    public void setGuardrailMessageAbuse(String guardrailMessageAbuse) { this.guardrailMessageAbuse = guardrailMessageAbuse; }
    public String getGuardrailMessageGibberish() { return guardrailMessageGibberish; }
    public void setGuardrailMessageGibberish(String guardrailMessageGibberish) { this.guardrailMessageGibberish = guardrailMessageGibberish; }
    public String getWebFlowsRoutingConfigJson() { return webFlowsRoutingConfigJson; }
    public void setWebFlowsRoutingConfigJson(String webFlowsRoutingConfigJson) { this.webFlowsRoutingConfigJson = webFlowsRoutingConfigJson; }
    public String getPrivacyPolicyUrl() { return privacyPolicyUrl; }
    public void setPrivacyPolicyUrl(String privacyPolicyUrl) { this.privacyPolicyUrl = privacyPolicyUrl; }
    public String getTermsUrl() { return termsUrl; }
    public void setTermsUrl(String termsUrl) { this.termsUrl = termsUrl; }
    public String getGrievanceEmail() { return grievanceEmail; }
    public void setGrievanceEmail(String grievanceEmail) { this.grievanceEmail = grievanceEmail; }
    public String getConsentNoticeText() { return consentNoticeText; }
    public void setConsentNoticeText(String consentNoticeText) { this.consentNoticeText = consentNoticeText; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getGoogleMapsUrl() { return googleMapsUrl; }
    public void setGoogleMapsUrl(String googleMapsUrl) { this.googleMapsUrl = googleMapsUrl; }

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
        private String returningMessage;
        private String businessSubType;
        private String logoUrl;
        private String widgetIconUrl;
        private Boolean showWatermark;
        private List<WidgetCtaDTO> ctaButtons;
        private List<MenuSectionDTO> menuSections;
        private String aboutUs;
        private String aiResponseMenuJson;
        private String flowCancelMenuJson;
        private String flowCompletionMenuJson;
        private String guardrailMessageAbuse;
        private String guardrailMessageGibberish;
        private String webFlowsRoutingConfigJson;
        private String privacyPolicyUrl;
        private String termsUrl;
        private String grievanceEmail;
        private String consentNoticeText;
        private String address;
        private Double latitude;
        private Double longitude;
        private String phone;
        private String email;
        private String googleMapsUrl;

        public ThemeConfigDTOBuilder primaryColor(String primaryColor) { this.primaryColor = primaryColor; return this; }
        public ThemeConfigDTOBuilder secondaryColor(String secondaryColor) { this.secondaryColor = secondaryColor; return this; }
        public ThemeConfigDTOBuilder accentColor(String accentColor) { this.accentColor = accentColor; return this; }
        public ThemeConfigDTOBuilder backgroundColor(String backgroundColor) { this.backgroundColor = backgroundColor; return this; }
        public ThemeConfigDTOBuilder fontFamily(String fontFamily) { this.fontFamily = fontFamily; return this; }
        public ThemeConfigDTOBuilder nicheIcon(String nicheIcon) { this.nicheIcon = nicheIcon; return this; }
        public ThemeConfigDTOBuilder businessName(String businessName) { this.businessName = businessName; return this; }
        public ThemeConfigDTOBuilder welcomeMessage(String welcomeMessage) { this.welcomeMessage = welcomeMessage; return this; }
        public ThemeConfigDTOBuilder returningMessage(String returningMessage) { this.returningMessage = returningMessage; return this; }
        public ThemeConfigDTOBuilder businessSubType(String businessSubType) { this.businessSubType = businessSubType; return this; }
        public ThemeConfigDTOBuilder logoUrl(String logoUrl) { this.logoUrl = logoUrl; return this; }
        public ThemeConfigDTOBuilder widgetIconUrl(String widgetIconUrl) { this.widgetIconUrl = widgetIconUrl; return this; }
        public ThemeConfigDTOBuilder showWatermark(Boolean showWatermark) { this.showWatermark = showWatermark; return this; }
        public ThemeConfigDTOBuilder ctaButtons(List<WidgetCtaDTO> ctaButtons) { this.ctaButtons = ctaButtons; return this; }
        public ThemeConfigDTOBuilder menuSections(List<MenuSectionDTO> menuSections) { this.menuSections = menuSections; return this; }
        public ThemeConfigDTOBuilder aboutUs(String aboutUs) { this.aboutUs = aboutUs; return this; }
        public ThemeConfigDTOBuilder aiResponseMenuJson(String aiResponseMenuJson) { this.aiResponseMenuJson = aiResponseMenuJson; return this; }
        public ThemeConfigDTOBuilder flowCancelMenuJson(String flowCancelMenuJson) { this.flowCancelMenuJson = flowCancelMenuJson; return this; }
        public ThemeConfigDTOBuilder flowCompletionMenuJson(String flowCompletionMenuJson) { this.flowCompletionMenuJson = flowCompletionMenuJson; return this; }
        public ThemeConfigDTOBuilder guardrailMessageAbuse(String guardrailMessageAbuse) { this.guardrailMessageAbuse = guardrailMessageAbuse; return this; }
        public ThemeConfigDTOBuilder guardrailMessageGibberish(String guardrailMessageGibberish) { this.guardrailMessageGibberish = guardrailMessageGibberish; return this; }
        public ThemeConfigDTOBuilder webFlowsRoutingConfigJson(String webFlowsRoutingConfigJson) { this.webFlowsRoutingConfigJson = webFlowsRoutingConfigJson; return this; }
        public ThemeConfigDTOBuilder privacyPolicyUrl(String privacyPolicyUrl) { this.privacyPolicyUrl = privacyPolicyUrl; return this; }
        public ThemeConfigDTOBuilder termsUrl(String termsUrl) { this.termsUrl = termsUrl; return this; }
        public ThemeConfigDTOBuilder grievanceEmail(String grievanceEmail) { this.grievanceEmail = grievanceEmail; return this; }
        public ThemeConfigDTOBuilder consentNoticeText(String consentNoticeText) { this.consentNoticeText = consentNoticeText; return this; }
        public ThemeConfigDTOBuilder address(String address) { this.address = address; return this; }
        public ThemeConfigDTOBuilder latitude(Double latitude) { this.latitude = latitude; return this; }
        public ThemeConfigDTOBuilder longitude(Double longitude) { this.longitude = longitude; return this; }
        public ThemeConfigDTOBuilder phone(String phone) { this.phone = phone; return this; }
        public ThemeConfigDTOBuilder email(String email) { this.email = email; return this; }
        public ThemeConfigDTOBuilder googleMapsUrl(String googleMapsUrl) { this.googleMapsUrl = googleMapsUrl; return this; }

        public ThemeConfigDTO build() {
            ThemeConfigDTO dto = new ThemeConfigDTO(primaryColor, secondaryColor, accentColor, backgroundColor, fontFamily, nicheIcon, businessName, welcomeMessage, returningMessage, businessSubType, logoUrl, showWatermark, ctaButtons, menuSections, aboutUs, aiResponseMenuJson, flowCancelMenuJson, flowCompletionMenuJson);
            dto.setWidgetIconUrl(this.widgetIconUrl);
            dto.setGuardrailMessageAbuse(this.guardrailMessageAbuse);
            dto.setGuardrailMessageGibberish(this.guardrailMessageGibberish);
            dto.setWebFlowsRoutingConfigJson(this.webFlowsRoutingConfigJson);
            dto.setPrivacyPolicyUrl(this.privacyPolicyUrl);
            dto.setTermsUrl(this.termsUrl);
            dto.setGrievanceEmail(this.grievanceEmail);
            dto.setConsentNoticeText(this.consentNoticeText);
            dto.setAddress(this.address);
            dto.setLatitude(this.latitude);
            dto.setLongitude(this.longitude);
            dto.setPhone(this.phone);
            dto.setEmail(this.email);
            dto.setGoogleMapsUrl(this.googleMapsUrl);
            return dto;
        }
    }
}
