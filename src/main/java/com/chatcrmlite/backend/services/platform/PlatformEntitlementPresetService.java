package com.chatcrmlite.backend.services.platform;

import com.chatcrmlite.backend.models.entitlements.EntitlementCatalog;
import com.chatcrmlite.backend.models.entitlements.EntitlementDefinition;
import com.chatcrmlite.backend.models.entitlements.EntitlementMutability;
import com.chatcrmlite.backend.models.entitlements.OverrideAction;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class PlatformEntitlementPresetService {

    public record PresetDefinition(
        String id,
        String name,
        String description,
        Map<String, OverrideAction> pageOverrides,
        Map<String, OverrideAction> settingOverrides,
        Map<String, OverrideAction> serviceOverrides
    ) {}

    public List<PresetDefinition> getAvailablePresets() {
        return List.of(
            createFullSuitePreset(),
            createWhatsAppStarterPreset(),
            createInquiryDeskPreset(),
            createSalesCrmPreset(),
            createResetInheritPreset()
        );
    }

    public Optional<PresetDefinition> getPresetById(String presetId) {
        if (presetId == null) return Optional.empty();
        return getAvailablePresets().stream()
                .filter(p -> p.id().equalsIgnoreCase(presetId))
                .findFirst();
    }

    private PresetDefinition createFullSuitePreset() {
        Map<String, OverrideAction> pages = new HashMap<>();
        Map<String, OverrideAction> settings = new HashMap<>();
        Map<String, OverrideAction> services = new HashMap<>();

        for (EntitlementDefinition def : EntitlementCatalog.getAll()) {
            if (def.mutability() == EntitlementMutability.ALWAYS_ENABLED) continue;
            switch (def.type()) {
                case PAGE -> pages.put(def.key(), OverrideAction.ALLOW);
                case SETTING -> settings.put(def.key(), OverrideAction.ALLOW);
                case SERVICE -> services.put(def.key(), OverrideAction.ALLOW);
            }
        }

        return new PresetDefinition(
            "FULL_SUITE",
            "Enterprise Full Suite",
            "Unlocks all available pages, advanced marketing tools, AI knowledge base, and backend services.",
            pages, settings, services
        );
    }

    private PresetDefinition createWhatsAppStarterPreset() {
        Map<String, OverrideAction> pages = new HashMap<>();
        Map<String, OverrideAction> settings = new HashMap<>();
        Map<String, OverrideAction> services = new HashMap<>();

        // WhatsApp Allowed
        pages.put("PAGE_INBOX", OverrideAction.ALLOW);
        pages.put("PAGE_CHATROOM", OverrideAction.ALLOW);
        pages.put("PAGE_BROADCASTS", OverrideAction.ALLOW);
        pages.put("PAGE_META_CONFIG", OverrideAction.ALLOW);
        pages.put("PAGE_CONTACTS", OverrideAction.ALLOW);

        // Deny complex add-ons
        pages.put("PAGE_EMAILS", OverrideAction.DENY);
        pages.put("PAGE_KNOWLEDGE_BASE", OverrideAction.DENY);
        pages.put("PAGE_APPOINTMENTS", OverrideAction.DENY);
        pages.put("PAGE_BOOKING", OverrideAction.DENY);

        services.put("SERVICE_WHATSAPP_API", OverrideAction.ALLOW);
        services.put("SERVICE_EMAIL_DISPATCH", OverrideAction.DENY);
        services.put("SERVICE_AI_RAG_LLM", OverrideAction.DENY);

        settings.put("SETTINGS_MENU_BUTTONS", OverrideAction.ALLOW);
        settings.put("SETTINGS_BROADCAST_FILTERS", OverrideAction.ALLOW);
        settings.put("SETTINGS_EMAIL_PROVIDERS", OverrideAction.DENY);

        return new PresetDefinition(
            "WHATSAPP_STARTER",
            "WhatsApp Messaging Starter",
            "Optimized for direct WhatsApp customer support, messaging broadcasts, and contact handling.",
            pages, settings, services
        );
    }

    private PresetDefinition createInquiryDeskPreset() {
        Map<String, OverrideAction> pages = new HashMap<>();
        Map<String, OverrideAction> settings = new HashMap<>();
        Map<String, OverrideAction> services = new HashMap<>();

        pages.put("PAGE_INBOX", OverrideAction.ALLOW);
        pages.put("PAGE_CHATROOM", OverrideAction.ALLOW);
        pages.put("PAGE_TICKETS", OverrideAction.ALLOW);
        pages.put("PAGE_KNOWLEDGE_BASE", OverrideAction.ALLOW);
        pages.put("PAGE_CONTACTS", OverrideAction.ALLOW);

        pages.put("PAGE_BROADCASTS", OverrideAction.DENY);
        pages.put("PAGE_EMAILS", OverrideAction.DENY);
        pages.put("PAGE_PIPELINE", OverrideAction.DENY);

        services.put("SERVICE_AI_RAG_LLM", OverrideAction.ALLOW);
        services.put("SERVICE_WHATSAPP_API", OverrideAction.ALLOW);

        settings.put("SETTINGS_SUPPORT_CATEGORIES", OverrideAction.ALLOW);

        return new PresetDefinition(
            "INQUIRY_DESK",
            "Support Desk & AI Assistant",
            "Focuses on ticketing, AI-assisted FAQ resolution, and live customer inquiries.",
            pages, settings, services
        );
    }

    private PresetDefinition createSalesCrmPreset() {
        Map<String, OverrideAction> pages = new HashMap<>();
        Map<String, OverrideAction> settings = new HashMap<>();
        Map<String, OverrideAction> services = new HashMap<>();

        pages.put("PAGE_PIPELINE", OverrideAction.ALLOW);
        pages.put("PAGE_CONTACTS", OverrideAction.ALLOW);
        pages.put("PAGE_APPOINTMENTS", OverrideAction.ALLOW);
        pages.put("PAGE_BOOKING", OverrideAction.ALLOW);
        pages.put("PAGE_REPORTS", OverrideAction.ALLOW);
        pages.put("PAGE_PROPERTIES", OverrideAction.ALLOW);

        pages.put("PAGE_BROADCASTS", OverrideAction.DENY);
        pages.put("PAGE_EMAILS", OverrideAction.DENY);

        return new PresetDefinition(
            "SALES_CRM",
            "Sales Pipeline & Bookings",
            "Designed for sales agents managing leads, booking meetings, and tracking deals.",
            pages, settings, services
        );
    }

    private PresetDefinition createResetInheritPreset() {
        Map<String, OverrideAction> pages = new HashMap<>();
        Map<String, OverrideAction> settings = new HashMap<>();
        Map<String, OverrideAction> services = new HashMap<>();

        for (EntitlementDefinition def : EntitlementCatalog.getAll()) {
            if (def.mutability() == EntitlementMutability.ALWAYS_ENABLED) continue;
            switch (def.type()) {
                case PAGE -> pages.put(def.key(), OverrideAction.INHERIT);
                case SETTING -> settings.put(def.key(), OverrideAction.INHERIT);
                case SERVICE -> services.put(def.key(), OverrideAction.INHERIT);
            }
        }

        return new PresetDefinition(
            "RESET_INHERIT",
            "Reset to Plan Defaults",
            "Removes all custom tenant overrides so permissions strictly inherit from the tenant base subscription plan.",
            pages, settings, services
        );
    }
}
