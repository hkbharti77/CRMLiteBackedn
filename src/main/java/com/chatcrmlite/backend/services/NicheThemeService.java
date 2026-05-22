package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.dto.ThemeConfigDTO;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class NicheThemeService {

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

        return ThemeConfigDTO.builder()
                .primaryColor(theme.primary)
                .secondaryColor(theme.secondary)
                .accentColor(theme.accent)
                .backgroundColor("#FFFFFF")
                .fontFamily(theme.font)
                .nicheIcon(theme.icon)
                .businessName(user.getBusinessName() != null ? user.getBusinessName() : "Assistant")
                .welcomeMessage(welcome)
                .businessSubType(slug)
                .logoUrl(user.getLogoUrl())
                .build();
    }

    private record NicheTheme(String primary, String secondary, String accent, String font, String icon) {}
}
