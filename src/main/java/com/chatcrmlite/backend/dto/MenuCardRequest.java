package com.chatcrmlite.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Used by the tenant admin (React Native app) when saving / updating custom
 * menu cards via the Menu Builder API.
 */
public class MenuCardRequest {

    @NotBlank(message = "section is required")
    @Size(max = 50)
    private String section = "SERVICES";

    @NotBlank(message = "title is required")
    @Size(max = 80)
    private String title;

    @Size(max = 120)
    private String subtitle;

    @Size(max = 40)
    private String icon = "briefcase";

    @NotBlank(message = "actionType is required")
    @Size(max = 20)
    private String actionType = "CATALOG";

    @Size(max = 500)
    private String actionPayload = "";

    private Integer displayOrder = 0;

    public MenuCardRequest() {}

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
