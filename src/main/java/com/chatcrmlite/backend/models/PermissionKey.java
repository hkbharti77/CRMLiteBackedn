package com.chatcrmlite.backend.models;

import java.util.Arrays;
import java.util.Optional;

public enum PermissionKey {
    MODULE_INBOX(false, null, "Inbox & Messaging"),
    MODULE_LEADS(false, null, "Leads & Contacts Management"),
    MODULE_CAMPAIGNS(false, null, "WhatsApp Campaigns & Broadcasts"),
    MODULE_ANALYTICS(false, null, "Analytics & Team Reports"),
    MODULE_TEAM(true, null, "Team & Staff Management"), // Admin/Owner Only
    MODULE_SETTINGS(false, null, "Settings Hub"),

    SETTINGS_PROFILE(false, MODULE_SETTINGS, "My Profile Settings"),
    SETTINGS_WHATSAPP(false, MODULE_SETTINGS, "Meta WhatsApp BSP Configuration"),
    SETTINGS_WIDGET(false, MODULE_SETTINGS, "Website Chat Widget Setup"),
    SETTINGS_LIVECHAT(false, MODULE_SETTINGS, "Live Support Queue & SLA Controls"),
    SETTINGS_BILLING(true, MODULE_SETTINGS, "Subscription Billing & Overrides"); // Admin/Owner Only

    private final boolean adminOnly;
    private final PermissionKey parentKey;
    private final String description;

    PermissionKey(boolean adminOnly, PermissionKey parentKey, String description) {
        this.adminOnly = adminOnly;
        this.parentKey = parentKey;
        this.description = description;
    }

    public boolean isAdminOnly() {
        return adminOnly;
    }

    public PermissionKey getParentKey() {
        return parentKey;
    }

    public String getDescription() {
        return description;
    }

    public static Optional<PermissionKey> fromString(String key) {
        if (key == null || key.trim().isEmpty()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(p -> p.name().equalsIgnoreCase(key.trim()))
                .findFirst();
    }
}
