package com.chatcrmlite.backend.dto;

/**
 * Menu card returned by the tenant Menu Builder API (includes section bucket).
 */
public class MenuBuilderCardDTO {
    private String section;
    private String title;
    private String subtitle;
    private String icon;
    private String actionType;
    private String actionPayload;
    private Integer displayOrder;

    public MenuBuilderCardDTO() {}

    public MenuBuilderCardDTO(String section, String title, String subtitle, String icon,
                              String actionType, String actionPayload, Integer displayOrder) {
        this.section = section;
        this.title = title;
        this.subtitle = subtitle;
        this.icon = icon;
        this.actionType = actionType;
        this.actionPayload = actionPayload;
        this.displayOrder = displayOrder;
    }

    public String getSection() { return section; }
    public void setSection(String section) { this.section = section; }

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

    public Integer getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(Integer displayOrder) { this.displayOrder = displayOrder; }
}
