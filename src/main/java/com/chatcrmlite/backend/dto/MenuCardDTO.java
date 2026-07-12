package com.chatcrmlite.backend.dto;

public class MenuCardDTO {
    private String title;
    private String subtitle;
    private String icon;
    private String actionType; // FLOW, LINK, SUPPORT
    private String actionPayload; // e.g., 'appointment', 'https://...', ''

    public MenuCardDTO() {}

    public MenuCardDTO(String title, String subtitle, String icon, String actionType, String actionPayload) {
        this.title = title;
        this.subtitle = subtitle;
        this.icon = icon;
        this.actionType = actionType;
        this.actionPayload = actionPayload;
    }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getSubtitle() { return subtitle; }
    public void setSubtitle(String subtitle) { this.subtitle = subtitle; }

    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }

    public String getActionType() { return actionType; }
    public void setActionType(String actionType) { this.actionType = actionType; }

    public String getActionPayload() { return actionPayload; }
    public void setActionPayload(String actionPayload) { this.actionPayload = actionPayload; }
}
