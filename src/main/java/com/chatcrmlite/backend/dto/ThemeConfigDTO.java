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
    private Boolean showWatermark;
    private List<WidgetCtaDTO> ctaButtons;
    private List<MenuSectionDTO> menuSections;
    private String aboutUs;
    private String aiResponseMenuJson;
    private String flowCancelMenuJson;
    private String flowCompletionMenuJson;
    private String guardrailMessageAbuse;
    private String guardrailMessageGibberish;

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
        private Boolean showWatermark;
        private List<WidgetCtaDTO> ctaButtons;
        private List<MenuSectionDTO> menuSections;
        private String aboutUs;
        private String aiResponseMenuJson;
        private String flowCancelMenuJson;
        private String flowCompletionMenuJson;
        private String guardrailMessageAbuse;
        private String guardrailMessageGibberish;

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
        public ThemeConfigDTOBuilder showWatermark(Boolean showWatermark) { this.showWatermark = showWatermark; return this; }
        public ThemeConfigDTOBuilder ctaButtons(List<WidgetCtaDTO> ctaButtons) { this.ctaButtons = ctaButtons; return this; }
        public ThemeConfigDTOBuilder menuSections(List<MenuSectionDTO> menuSections) { this.menuSections = menuSections; return this; }
        public ThemeConfigDTOBuilder aboutUs(String aboutUs) { this.aboutUs = aboutUs; return this; }
        public ThemeConfigDTOBuilder aiResponseMenuJson(String aiResponseMenuJson) { this.aiResponseMenuJson = aiResponseMenuJson; return this; }
        public ThemeConfigDTOBuilder flowCancelMenuJson(String flowCancelMenuJson) { this.flowCancelMenuJson = flowCancelMenuJson; return this; }
        public ThemeConfigDTOBuilder flowCompletionMenuJson(String flowCompletionMenuJson) { this.flowCompletionMenuJson = flowCompletionMenuJson; return this; }
        public ThemeConfigDTOBuilder guardrailMessageAbuse(String guardrailMessageAbuse) { this.guardrailMessageAbuse = guardrailMessageAbuse; return this; }
        public ThemeConfigDTOBuilder guardrailMessageGibberish(String guardrailMessageGibberish) { this.guardrailMessageGibberish = guardrailMessageGibberish; return this; }

        public ThemeConfigDTO build() {
            ThemeConfigDTO dto = new ThemeConfigDTO(primaryColor, secondaryColor, accentColor, backgroundColor, fontFamily, nicheIcon, businessName, welcomeMessage, returningMessage, businessSubType, logoUrl, showWatermark, ctaButtons, menuSections, aboutUs, aiResponseMenuJson, flowCancelMenuJson, flowCompletionMenuJson);
            dto.setGuardrailMessageAbuse(this.guardrailMessageAbuse);
            dto.setGuardrailMessageGibberish(this.guardrailMessageGibberish);
            return dto;
        }
    }
}
