package com.chatcrmlite.backend.dto;

public class WidgetCtaDTO {
    private String label;
    private String action;

    public WidgetCtaDTO() {}

    public WidgetCtaDTO(String label, String action) {
        this.label = label;
        this.action = action;
    }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
}
