package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.dto.ThemeConfigDTO;
import com.chatcrmlite.backend.models.CustomMenuCard;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.repositories.CustomMenuCardRepository;
import com.chatcrmlite.backend.services.tenant.QuotaEnforcerService;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import com.chatcrmlite.backend.dto.WidgetCtaDTO;
import com.chatcrmlite.backend.dto.MenuSectionDTO;
import com.chatcrmlite.backend.dto.MenuCardDTO;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class NicheThemeService {
    
    private final QuotaEnforcerService quotaEnforcerService;

    public NicheThemeService(QuotaEnforcerService quotaEnforcerService) {
        this.quotaEnforcerService = quotaEnforcerService;
    }

    @Autowired
    private FlowTemplateEngine templateEngine;

    @Autowired
    private CustomMenuCardRepository customMenuCardRepository;

    private static final Map<String, NicheTheme> THEMES = new HashMap<>();

    static {
        // 1. Auto Dealers
        THEMES.put("auto-used-car-dealers", new NicheTheme("#1E293B", "#334155", "#3B82F6", "Inter, sans-serif", "🚗"));
        // 2. Career Counselors
        THEMES.put("career-study-abroad-counselors", new NicheTheme("#1E3A8A", "#2563EB", "#F59E0B", "Merriweather, serif", "🎓"));
        // 3. Dental Clinics
        THEMES.put("dental-clinics", new NicheTheme("#0D9488", "#14B8A6", "#F0FDFA", "Inter, sans-serif", "🦷"));
        // 4. Wedding Planners
        THEMES.put("event-wedding-planners", new NicheTheme("#9D174D", "#BE185D", "#FBCFE8", "Playfair Display, serif", "✨"));
        // 5. Makeup Artists
        THEMES.put("freelance-makeup-artists-mua", new NicheTheme("#831843", "#9D174D", "#FCE7F3", "Montserrat, sans-serif", "💄"));
        // 6. Web Designers
        THEMES.put("freelance-web-graphic-designers", new NicheTheme("#4C1D95", "#5B21B6", "#A78BFA", "Fira Code, monospace", "💻"));
        // 7. Fitness Trainers
        THEMES.put("gym-personal-fitness-trainers", new NicheTheme("#7C2D12", "#9A3412", "#FB923C", "Oswald, sans-serif", "🏋️"));
        // 8. Ayurveda Doctors
        THEMES.put("homeopathy-ayurveda-doctors", new NicheTheme("#064E3B", "#065F46", "#D1FAE5", "Lora, serif", "🌿"));
        // 9. Independent Tutors
        THEMES.put("independent-tutors", new NicheTheme("#854D0E", "#A16207", "#FEF08A", "Quicksand, sans-serif", "📚"));
        // 10. Insurance Agents
        THEMES.put("insurance-agents", new NicheTheme("#1E3A8A", "#1E40AF", "#DBEAFE", "Roboto, sans-serif", "🛡️"));
        // 11. Interior Designers
        THEMES.put("interior-designers-architects", new NicheTheme("#451A03", "#78350F", "#FEF3C7", "Montserrat, sans-serif", "🏠"));
        // 12. Music Classes
        THEMES.put("music-art-classes", new NicheTheme("#4C1D95", "#6D28D9", "#DDD6FE", "Poppins, sans-serif", "🎵"));
        // 13. Physiotherapy
        THEMES.put("physiotherapy-chiropractic-centers", new NicheTheme("#1E3A8A", "#2563EB", "#E0F2FE", "Inter, sans-serif", "💆"));
        // 14. Premium Salons
        THEMES.put("premium-salons-hair-clinics", new NicheTheme("#111827", "#1F2937", "#F59E0B", "Playfair Display, serif", "✂️"));
        // 15. Travel Operators
        THEMES.put("premium-tour-travel-operators", new NicheTheme("#065F46", "#047857", "#34D399", "Raleway, sans-serif", "✈️"));
        // 16. Property Brokers
        THEMES.put("property-brokers", new NicheTheme("#111827", "#1E293B", "#64748B", "Cinzel, serif", "🏢"));
        // 17. Aesthetic Clinics
        THEMES.put("skin-aesthetic-clinics", new NicheTheme("#831843", "#9D174D", "#FDF2F8", "Inter, sans-serif", "✨"));
        // 18. Solar Installers
        THEMES.put("solar-panel-smart-home-installers", new NicheTheme("#064E3B", "#065F46", "#10B981", "Exo 2, sans-serif", "☀️"));
        // 19. Photographers
        THEMES.put("wedding-portrait-photographers", new NicheTheme("#111827", "#1F2937", "#9CA3AF", "Libre Baskerville, serif", "📸"));
        // 20. Yoga Instructors
        THEMES.put("yoga-meditation-instructors", new NicheTheme("#312E81", "#3730A3", "#C7D2FE", "Zen Loop, cursive", "🧘"));
        // 21. Generic/Corporate
        THEMES.put("generic", new NicheTheme("#0F172A", "#1E293B", "#3B82F6", "Inter, sans-serif", "🤖"));
    }

    public ThemeConfigDTO getThemeForUser(User user) {
        String slug = FlowTriggerEngine.toSlug(user.getBusinessSubType());
        NicheTheme theme = THEMES.getOrDefault(slug, THEMES.get("generic"));
        
        WhatsAppConfig config = user.getWhatsappConfig();
        String welcome = (config != null && config.getWelcomeMessage() != null) 
                ? config.getWelcomeMessage() 
                : "Hello! How can I help you today?";

        String returning = (config != null && config.getReturningMessage() != null)
                ? config.getReturningMessage()
                : "Welcome back! How can we assist you today?";

        String bName = user.getBusinessName() != null ? user.getBusinessName() : "our business";
        
        if (welcome != null && welcome.contains("{{business}}")) {
            welcome = welcome.replace("{{business}}", bName);
        }
        
        if (returning != null && returning.contains("{{business}}")) {
            returning = returning.replace("{{business}}", bName);
        }

        List<WidgetCtaDTO> ctaButtons = new ArrayList<>();
        boolean hasAppointment = Boolean.TRUE.equals(user.getForceShowAppointment());
        boolean hasBooking = Boolean.TRUE.equals(user.getForceShowBooking());
        boolean hasLead = Boolean.TRUE.equals(user.getForceShowLeads());

        FlowTemplateEngine.FlowBlueprint blueprint = templateEngine.getBlueprint(user.getBusinessSubType());
        if (blueprint != null) {
            com.chatcrmlite.backend.models.ConversationState.FlowType primaryFlow = blueprint.getFlowType();
            if (primaryFlow == com.chatcrmlite.backend.models.ConversationState.FlowType.APPOINTMENT) hasAppointment = true;
            if (primaryFlow == com.chatcrmlite.backend.models.ConversationState.FlowType.BOOKING) hasBooking = true;
            if (primaryFlow == com.chatcrmlite.backend.models.ConversationState.FlowType.LEAD_CAPTURE) hasLead = true;
        }

        if (hasAppointment) {
            ctaButtons.add(new WidgetCtaDTO(templateEngine.getTriggerButtonLabel(user, "appointment"), "APPOINTMENT"));
        }
        if (hasBooking) {
            ctaButtons.add(new WidgetCtaDTO(templateEngine.getTriggerButtonLabel(user, "booking"), "BOOKING"));
        }
        if (hasLead) {
            ctaButtons.add(new WidgetCtaDTO(templateEngine.getTriggerButtonLabel(user, "lead"), "LEAD"));
        }
        if (config != null && Boolean.TRUE.equals(config.getShowSupportFormButton())) {
            ctaButtons.add(new WidgetCtaDTO("🎫 Get Support", "SUPPORT"));
        }

        UUID tenantIdForQuota = user.getTenant() != null ? user.getTenant().getId() : user.getId();
        boolean allowBranding = quotaEnforcerService.isCustomWidgetBrandingAllowed(tenantIdForQuota);
        String finalLogoUrl = user.getLogoUrl() != null && !user.getLogoUrl().isBlank() ? user.getLogoUrl() : null;
        String finalWidgetIconUrl = user.getWidgetIconUrl() != null && !user.getWidgetIconUrl().isBlank() ? user.getWidgetIconUrl() : null;
        boolean showWatermark = !allowBranding;

        String finalPrimary = theme.primary;
        String finalSecondary = theme.secondary;
        
        if (user.getTenant() != null) {
            if (user.getTenant().getPrimaryColor() != null && !user.getTenant().getPrimaryColor().isBlank()) {
                finalPrimary = user.getTenant().getPrimaryColor();
            }
            if (user.getTenant().getSecondaryColor() != null && !user.getTenant().getSecondaryColor().isBlank()) {
                finalSecondary = user.getTenant().getSecondaryColor();
            }
        }

        List<MenuSectionDTO> menuSections = buildMenuSections(user, slug, hasAppointment, hasBooking, hasLead, config);

        return ThemeConfigDTO.builder()
                .primaryColor(finalPrimary)
                .secondaryColor(finalSecondary)
                .accentColor(theme.accent)
                .backgroundColor("#FFFFFF")
                .fontFamily(theme.font)
                .nicheIcon(theme.icon)
                .businessName(user.getBusinessName() != null ? user.getBusinessName() : "Assistant")
                .welcomeMessage(welcome)
                .returningMessage(returning)
                .businessSubType(slug)
                .logoUrl(finalLogoUrl)
                .widgetIconUrl(finalWidgetIconUrl)
                .showWatermark(showWatermark)
                .ctaButtons(ctaButtons)
                .menuSections(menuSections)
                .aboutUs(config != null && Boolean.TRUE.equals(config.getShowAboutContact()) ? user.getAboutUs() : null)
                .aiResponseMenuJson(config != null ? config.getAiResponseMenuJson() : null)
                .flowCancelMenuJson(config != null ? config.getFlowCancelMenuJson() : null)
                .flowCompletionMenuJson(config != null ? config.getFlowCompletionMenuJson() : null)
                .guardrailMessageAbuse(config != null ? config.getGuardrailMessageAbuse() : null)
                .guardrailMessageGibberish(config != null ? config.getGuardrailMessageGibberish() : null)
                .webFlowsRoutingConfigJson(user.getWebFlowsRoutingConfigJson())
                .build();
    }

    private List<MenuSectionDTO> buildMenuSections(User user, String slug, boolean hasAppt, boolean hasBooking, boolean hasLead, WhatsAppConfig config) {
        List<MenuSectionDTO> sections = new ArrayList<>();

        // 1. CONNECT SECTION
        List<MenuCardDTO> connectCards = new ArrayList<>();
        if (hasAppt) {
            connectCards.add(new MenuCardDTO("Book Appointment", "Schedule a time", "calendar", "FLOW", "appointment"));
        }
        if (hasBooking) {
            connectCards.add(new MenuCardDTO("Book a Service", "Reserve your spot", "calendar", "FLOW", "booking"));
        }
        if (hasLead) {
            connectCards.add(new MenuCardDTO("Get a Quote", "Contact our team", "briefcase", "FLOW", "lead"));
        }
        if (config != null && Boolean.TRUE.equals(config.getShowSupportFormButton())) {
            connectCards.add(new MenuCardDTO("Support Center", "Get help now", "settings", "SUPPORT", ""));
        }
        if (!connectCards.isEmpty()) {
            sections.add(new MenuSectionDTO("CONNECT", connectCards));
        }

        // 2. SERVICES SECTION
        // Priority: tenant's custom cards > niche defaults
        Tenant tenant = user.getTenant();
        List<MenuCardDTO> serviceCards = new ArrayList<>();

        boolean hasCustomCards = tenant != null && customMenuCardRepository.existsByTenant(tenant);

        if (hasCustomCards) {
            // Use tenant's custom-built cards
            List<CustomMenuCard> customCards = customMenuCardRepository
                    .findByTenantAndSectionOrderByDisplayOrderAsc(tenant, "SERVICES");
            for (CustomMenuCard c : customCards) {
                serviceCards.add(new MenuCardDTO(c.getTitle(), c.getSubtitle(), c.getIcon(), c.getActionType(), c.getActionPayload()));
            }
            // Always append About Us if the tenant hasn't added it themselves
            boolean hasAbout = customCards.stream().anyMatch(c -> "ABOUT".equals(c.getActionType()));
            if (!hasAbout) {
                serviceCards.add(new MenuCardDTO("About Us", "Learn more", "info", "ABOUT", "about"));
            }
        } else {
            // Fall back to niche-based default cards
            serviceCards.addAll(getDefaultServiceCards(slug));
        }

        sections.add(new MenuSectionDTO("SERVICES", serviceCards));

        // 3. RESOURCES SECTION
        List<MenuCardDTO> resourceCards = new ArrayList<>();
        resourceCards.add(new MenuCardDTO("Careers", "Join our team", "doc", "LINK", "#careers"));
        resourceCards.add(new MenuCardDTO("Blog", "Read our articles", "doc", "LINK", "#blog"));
        sections.add(new MenuSectionDTO("RESOURCES", resourceCards));

        return sections;
    }

    public List<MenuCardDTO> getDefaultServiceCards(String slug) {
        List<MenuCardDTO> serviceCards = new ArrayList<>();
        switch (slug) {
            case "real-estate", "property-brokers" ->
                serviceCards.add(new MenuCardDTO("Property Listings", "View our properties", "home", "CATALOG", "services"));
            case "dental-clinics" ->
                serviceCards.add(new MenuCardDTO("Dental Services", "View our treatments", "briefcase", "CATALOG", "services"));
            case "auto-used-car-dealers" ->
                serviceCards.add(new MenuCardDTO("View Inventory", "Browse used cars", "briefcase", "CATALOG", "services"));
            case "freelance-web-graphic-designers" ->
                serviceCards.add(new MenuCardDTO("Portfolio", "View past work", "briefcase", "CATALOG", "services"));
            case "retail" ->
                serviceCards.add(new MenuCardDTO("Product Catalog", "Browse products", "briefcase", "CATALOG", "services"));
            case "premium-salons-hair-clinics", "freelance-makeup-artists-mua" ->
                serviceCards.add(new MenuCardDTO("Service Menu", "View all services", "briefcase", "CATALOG", "services"));
            case "yoga-meditation-instructors", "music-art-classes" ->
                serviceCards.add(new MenuCardDTO("View Classes", "See our schedule", "calendar", "CATALOG", "services"));
            case "premium-tour-travel-operators", "event-wedding-planners" ->
                serviceCards.add(new MenuCardDTO("View Packages", "Explore our offers", "briefcase", "CATALOG", "services"));
            case "solar-panel-smart-home-installers" ->
                serviceCards.add(new MenuCardDTO("Our Products", "See what we install", "briefcase", "CATALOG", "services"));
            default ->
                serviceCards.add(new MenuCardDTO("Our Services", "What we offer", "briefcase", "CATALOG", "services"));
        }
        
        // Add common cards for the custom menu
        serviceCards.add(new MenuCardDTO("Special Offers", "View current deals", "tag", "LINK", "#offers"));
        serviceCards.add(new MenuCardDTO("FAQs", "Common questions", "info", "LINK", "#faqs"));
        
        // Always add About Us for all niches in the default case
        serviceCards.add(new MenuCardDTO("About Us", "Learn more", "info", "ABOUT", "about"));
        return serviceCards;
    }

    private record NicheTheme(String primary, String secondary, String accent, String font, String icon) {}
}
