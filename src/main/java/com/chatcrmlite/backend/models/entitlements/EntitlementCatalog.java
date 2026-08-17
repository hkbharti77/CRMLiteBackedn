package com.chatcrmlite.backend.models.entitlements;

import java.util.*;

public final class EntitlementCatalog {

    private static final List<EntitlementDefinition> DEFINITIONS = new ArrayList<>();
    private static final Map<String, EntitlementDefinition> BY_KEY = new LinkedHashMap<>();

    static {
        // --- Core Pages ---
        register(new EntitlementDefinition("PAGE_DASHBOARD", EntitlementType.PAGE, "Dashboard", "Core", "Main analytics overview and metrics", List.of(), EntitlementMutability.ALWAYS_ENABLED));
        register(new EntitlementDefinition("PAGE_INBOX", EntitlementType.PAGE, "Inbox & Live Chat", "Communication", "Unified real-time conversations desk", List.of(), EntitlementMutability.OVERRIDABLE));
        register(new EntitlementDefinition("PAGE_CHATROOM", EntitlementType.PAGE, "Chat Room", "Communication", "Dedicated contact chatroom view", List.of(), EntitlementMutability.OVERRIDABLE));
        register(new EntitlementDefinition("PAGE_PIPELINE", EntitlementType.PAGE, "Leads & Pipeline", "CRM", "Kanban lead management pipeline", List.of(), EntitlementMutability.OVERRIDABLE));
        register(new EntitlementDefinition("PAGE_BROADCASTS", EntitlementType.PAGE, "Broadcast Campaigns", "Marketing", "WhatsApp mass broadcast campaigns and templates", List.of("SERVICE_WHATSAPP_API"), EntitlementMutability.OVERRIDABLE));
        register(new EntitlementDefinition("PAGE_META_CONFIG", EntitlementType.PAGE, "Meta WhatsApp Config", "Integration", "WhatsApp Cloud API credentials & settings", List.of("SERVICE_WHATSAPP_API"), EntitlementMutability.OVERRIDABLE));
        register(new EntitlementDefinition("PAGE_KNOWLEDGE_BASE", EntitlementType.PAGE, "AI Knowledge Base", "AI & Automation", "RAG vector search documents and FAQ embedding", List.of("SERVICE_AI_RAG_LLM"), EntitlementMutability.OVERRIDABLE));
        register(new EntitlementDefinition("PAGE_APPOINTMENTS", EntitlementType.PAGE, "Appointments Calendar", "Scheduling", "Meeting bookings, staff schedules & Google Meet sync", List.of(), EntitlementMutability.OVERRIDABLE));
        register(new EntitlementDefinition("PAGE_BOOKING", EntitlementType.PAGE, "Booking Links", "Scheduling", "Public customer booking links", List.of(), EntitlementMutability.OVERRIDABLE));
        register(new EntitlementDefinition("PAGE_TICKETS", EntitlementType.PAGE, "Support Tickets Desk", "Helpdesk", "Customer support ticket workflow and SLAs", List.of(), EntitlementMutability.OVERRIDABLE));
        register(new EntitlementDefinition("PAGE_EMAILS", EntitlementType.PAGE, "Email Marketing", "Marketing", "Email campaigns, templates, and dispatch", List.of("SERVICE_EMAIL_DISPATCH"), EntitlementMutability.OVERRIDABLE));
        register(new EntitlementDefinition("PAGE_PROPERTIES", EntitlementType.PAGE, "Real Estate & Catalog", "Catalog", "Property and product catalog inventory", List.of(), EntitlementMutability.OVERRIDABLE));
        register(new EntitlementDefinition("PAGE_REPORTS", EntitlementType.PAGE, "Reports & Analytics", "Analytics", "Performance telemetry, agent stats & export reports", List.of(), EntitlementMutability.OVERRIDABLE));
        register(new EntitlementDefinition("PAGE_TEAM", EntitlementType.PAGE, "Team Management", "Administration", "Agent invites, user roles, and internal access levels", List.of(), EntitlementMutability.OVERRIDABLE));
        register(new EntitlementDefinition("PAGE_CONTACTS", EntitlementType.PAGE, "CRM Contacts", "CRM", "Unified customer address book, tags & custom fields", List.of(), EntitlementMutability.OVERRIDABLE));

        // --- Settings Tabs ---
        register(new EntitlementDefinition("SETTINGS_PROFILE", EntitlementType.SETTING, "Account Profile", "Account", "Personal and organization details", List.of(), EntitlementMutability.ALWAYS_ENABLED));
        register(new EntitlementDefinition("SETTINGS_SECURITY", EntitlementType.SETTING, "Security & 2FA", "Account", "Password reset and authentication security", List.of(), EntitlementMutability.ALWAYS_ENABLED));
        register(new EntitlementDefinition("SETTINGS_CALENDAR", EntitlementType.SETTING, "Google Meet Sync", "Account", "Google Calendar integration", List.of(), EntitlementMutability.OVERRIDABLE));
        register(new EntitlementDefinition("SETTINGS_BILLING", EntitlementType.SETTING, "Subscription & Invoices", "Account", "Tenant plan usage and invoices", List.of(), EntitlementMutability.ALWAYS_ENABLED));
        register(new EntitlementDefinition("SETTINGS_BRANDING", EntitlementType.SETTING, "Brand & Identity", "Appearance", "Custom logo, theme colors, widget styles", List.of(), EntitlementMutability.OVERRIDABLE));
        register(new EntitlementDefinition("SETTINGS_NOTIFICATIONS", EntitlementType.SETTING, "Push Notifications", "Appearance", "Web push and system alert settings", List.of(), EntitlementMutability.OVERRIDABLE));
        register(new EntitlementDefinition("SETTINGS_MENU_BUTTONS", EntitlementType.SETTING, "WhatsApp Menu & Buttons", "Configuration", "Chat interactive buttons", List.of("SERVICE_WHATSAPP_API"), EntitlementMutability.OVERRIDABLE));
        register(new EntitlementDefinition("SETTINGS_WHATSAPP_FLOWS", EntitlementType.SETTING, "WhatsApp Native Flows", "Configuration", "WhatsApp native structured forms", List.of("SERVICE_FLOW_AUTOMATION"), EntitlementMutability.OVERRIDABLE));
        register(new EntitlementDefinition("SETTINGS_MENU_BUILDER", EntitlementType.SETTING, "Menu Builder", "Configuration", "Interactive customer card and menu builder", List.of(), EntitlementMutability.OVERRIDABLE));
        register(new EntitlementDefinition("SETTINGS_PRODUCTS", EntitlementType.SETTING, "Products & Services", "Configuration", "Manage tenant products and pricing", List.of(), EntitlementMutability.OVERRIDABLE));
        register(new EntitlementDefinition("SETTINGS_FORM_FIELDS", EntitlementType.SETTING, "Custom Form Fields", "Configuration", "Dynamic custom field configurations", List.of(), EntitlementMutability.OVERRIDABLE));
        register(new EntitlementDefinition("SETTINGS_SUBMENUS", EntitlementType.SETTING, "Custom Sub-Menus", "Configuration", "Nested menus and options", List.of(), EntitlementMutability.OVERRIDABLE));
        register(new EntitlementDefinition("SETTINGS_EMAIL_PROVIDERS", EntitlementType.SETTING, "Email Providers", "Configuration", "SES, Brevo, custom SMTP setups", List.of("SERVICE_EMAIL_DISPATCH"), EntitlementMutability.OVERRIDABLE));
        register(new EntitlementDefinition("SETTINGS_BROADCAST_FILTERS", EntitlementType.SETTING, "Broadcast CSV Filters", "Configuration", "Audience filter rules for bulk campaigns", List.of("SERVICE_WHATSAPP_API"), EntitlementMutability.OVERRIDABLE));
        register(new EntitlementDefinition("SETTINGS_QUICK_RESPONSES", EntitlementType.SETTING, "Quick Responses", "Configuration", "Canned replies snippet library", List.of(), EntitlementMutability.OVERRIDABLE));
        register(new EntitlementDefinition("SETTINGS_FLOW_CTA", EntitlementType.SETTING, "Flow CTA Buttons", "Configuration", "Workflow completion and trigger buttons", List.of("SERVICE_FLOW_AUTOMATION"), EntitlementMutability.OVERRIDABLE));
        register(new EntitlementDefinition("SETTINGS_SUPPORT_CATEGORIES", EntitlementType.SETTING, "Support Categories", "AI & Knowledge", "AI ticket auto-categorization", List.of("SERVICE_AI_RAG_LLM"), EntitlementMutability.OVERRIDABLE));
        register(new EntitlementDefinition("SETTINGS_SYSTEM_HEALTH", EntitlementType.SETTING, "System Health", "System", "Live backend connectivity telemetry", List.of(), EntitlementMutability.OVERRIDABLE));
        register(new EntitlementDefinition("SETTINGS_HELP", EntitlementType.SETTING, "Priority Help Desk", "Support", "Direct contact support", List.of(), EntitlementMutability.ALWAYS_ENABLED));

        // --- Core Backend Services ---
        register(new EntitlementDefinition("SERVICE_WHATSAPP_API", EntitlementType.SERVICE, "WhatsApp Cloud API Service", "Backend Services", "Official Meta WhatsApp API message processing", List.of(), EntitlementMutability.OVERRIDABLE));
        register(new EntitlementDefinition("SERVICE_EMAIL_DISPATCH", EntitlementType.SERVICE, "Email Engine Service", "Backend Services", "Outbound bulk email and transactional dispatch", List.of(), EntitlementMutability.OVERRIDABLE));
        register(new EntitlementDefinition("SERVICE_AI_RAG_LLM", EntitlementType.SERVICE, "AI RAG & LLM Inference", "Backend Services", "Vector search retrieval and LLM response generation", List.of(), EntitlementMutability.OVERRIDABLE));
        register(new EntitlementDefinition("SERVICE_FLOW_AUTOMATION", EntitlementType.SERVICE, "Interactive Flows Engine", "Backend Services", "Native interactive flows execution engine", List.of("SERVICE_WHATSAPP_API"), EntitlementMutability.OVERRIDABLE));
        register(new EntitlementDefinition("SERVICE_BULK_UPLOAD", EntitlementType.SERVICE, "Bulk CSV Processing Engine", "Backend Services", "High-throughput background CSV processing", List.of(), EntitlementMutability.OVERRIDABLE));
    }

    private static void register(EntitlementDefinition def) {
        DEFINITIONS.add(def);
        BY_KEY.put(def.key(), def);
    }

    public static List<EntitlementDefinition> getAll() {
        return Collections.unmodifiableList(DEFINITIONS);
    }

    public static Optional<EntitlementDefinition> getByKey(String key) {
        return Optional.ofNullable(BY_KEY.get(key));
    }

    public static boolean isAlwaysEnabled(String key) {
        EntitlementDefinition def = BY_KEY.get(key);
        return def != null && def.mutability() == EntitlementMutability.ALWAYS_ENABLED;
    }

    private EntitlementCatalog() {}
}
