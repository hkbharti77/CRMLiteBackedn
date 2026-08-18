package com.chatcrmlite.backend.util;

import java.util.Set;

/**
 * Normalizes menu card action types between admin UIs and the web widget.
 * Legacy crmpannel values (EXTERNAL_LINK, ABOUT_US, etc.) are mapped to widget values.
 */
public final class MenuActionTypes {

    private static final Set<String> WIDGET_ACTIONS = Set.of(
            "CATALOG", "LINK", "ABOUT", "SUPPORT", "FLOW"
    );

    private MenuActionTypes() {}

    public static String normalize(String actionType) {
        if (actionType == null || actionType.isBlank()) {
            return "CATALOG";
        }
        return switch (actionType.trim().toUpperCase()) {
            case "EXTERNAL_LINK" -> "LINK";
            case "ABOUT_US" -> "ABOUT";
            case "CONTACT_SUPPORT" -> "SUPPORT";
            case "CUSTOM_RESPONSE" -> "LINK";
            default -> actionType.trim().toUpperCase();
        };
    }

    public static boolean isWidgetAction(String actionType) {
        return WIDGET_ACTIONS.contains(normalize(actionType));
    }
}
