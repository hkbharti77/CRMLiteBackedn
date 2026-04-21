package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.models.BusinessSubCategory;
import com.chatcrmlite.backend.models.ConversationState.FlowType;
import com.chatcrmlite.backend.repositories.BusinessSubCategoryRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@NoArgsConstructor
public class FlowTemplateEngine {

    @Autowired
    private BusinessSubCategoryRepository subCategoryRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private final Map<String, LabelPair> labelCache = new ConcurrentHashMap<>();
    private final Map<String, FlowBlueprint> blueprintCache = new ConcurrentHashMap<>();

    private record LabelPair(String trigger, String services) {}

    // ════════════════════════════════════════════════════════════════════════
    //  Dynamic Label Logic (Database Driven)
    // ════════════════════════════════════════════════════════════════════════

    public String getTriggerButtonLabel(String subCategoryName) {
        return getLabels(subCategoryName).trigger();
    }

    /**
     * Retrieves the services catalog label (e.g. "📋 View Services") from the DB.
     */
    public String getServicesLabel(String subCategoryName) {
        return getLabels(subCategoryName).services();
    }

    /**
     * Meta limit: List Row Title <= 24 chars.
     */
    public String getTriggerListLabel(String subCategoryName) {
        String label = getTriggerButtonLabel(subCategoryName);
        return label.length() > 24 ? label.substring(0, 24) : label;
    }

    private LabelPair getLabels(String subCategoryName) {
        if (subCategoryName == null || subCategoryName.isBlank()) {
            return new LabelPair("Enquire Now", "📋 View Services");
        }

        return labelCache.computeIfAbsent(subCategoryName, name -> {
            Optional<BusinessSubCategory> subOpt = subCategoryRepository.findByName(name);
            LabelPair defaults = getNicheDefaults(name);
            
            if (subOpt.isPresent()) {
                BusinessSubCategory sub = subOpt.get();
                String trigger = (sub.getTriggerLabel() != null && !sub.getTriggerLabel().isBlank()) 
                                 ? sub.getTriggerLabel() : defaults.trigger();
                String services = (sub.getServicesLabel() != null && !sub.getServicesLabel().isBlank()) 
                                  ? sub.getServicesLabel() : defaults.services();
                return new LabelPair(trigger, services);
            }
            return defaults;
        });
    }

    private LabelPair getNicheDefaults(String name) {
        if (name == null) return new LabelPair("Enquire Now", "📋 View Services");
        String cat = name.toLowerCase();
        
        if (cat.contains("dental")) return new LabelPair("🦷 Book Appointment", "📋 View Treatments");
        if (cat.contains("real estate") || cat.contains("property")) return new LabelPair("🏠 Enquire Property", "📋 View Properties");
        if (cat.contains("salon") || cat.contains("spa")) return new LabelPair("✂️ Book Service", "📋 Service Menu");
        if (cat.contains("skin") || cat.contains("aesthetic")) return new LabelPair("💆 Book Treatment", "📋 Our Services");
        if (cat.contains("gym") || cat.contains("fitness")) return new LabelPair("💪 Join Now", "📋 View Plans");
        if (cat.contains("tour") || cat.contains("travel")) return new LabelPair("✈️ Plan Trip", "📋 View Packages");
        if (cat.contains("wedding") || cat.contains("event")) return new LabelPair("🎉 Planning Help", "📋 Our Packages");
        if (cat.contains("auto") || cat.contains("car")) return new LabelPair("🚗 Book Test Drive", "📋 View Cars");
        if (cat.contains("photograph")) return new LabelPair("📸 Book Shoot", "📋 View Portfolio");
        if (cat.contains("makeup") || cat.contains("mua")) return new LabelPair("💄 Book Session", "📋 Price List");
        if (cat.contains("yoga") || cat.contains("meditation")) return new LabelPair("🧘 Join Class", "📋 View Classes");
        if (cat.contains("physio") || cat.contains("chiro")) return new LabelPair("⚕️ Book Session", "📋 Our Services");
        if (cat.contains("solar") || cat.contains("smart home")) return new LabelPair("☀️ Free Quote", "📋 Our Products");
        if (cat.contains("insurance")) return new LabelPair("🛡️ Get Quote", "📋 Policy Types");
        if (cat.contains("interior") || cat.contains("architect")) return new LabelPair("🏡 Project Inquiry", "📋 View Portfolio");
        if (cat.contains("tutor") || cat.contains("teacher")) return new LabelPair("📚 Book Trial", "📋 View Subjects");
        if (cat.contains("study abroad") || cat.contains("counselor")) return new LabelPair("🎓 Free Counseling", "📋 Our Services");
        if (cat.contains("music") || cat.contains("art")) return new LabelPair("🎸 Join Class", "📋 Course List");
        if (cat.contains("homeopathy") || cat.contains("ayurveda")) return new LabelPair("🌿 Book Consultation", "📋 Our remedies");
        if (cat.contains("web") || cat.contains("graphic") || cat.contains("design")) return new LabelPair("💻 Start Project", "📋 Portfolio");
        
        return new LabelPair("Enquire Now", "📋 View Services");
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Flow Blueprint Logic (Dynamic via JSON + Fallbacks)
    // ════════════════════════════════════════════════════════════════════════

    public FlowBlueprint getBlueprint(String subCategoryName) {
        if (subCategoryName == null || subCategoryName.isBlank()) return genericEnquiry();
        
        String slug = toSlug(subCategoryName);
        
        return blueprintCache.computeIfAbsent(slug, s -> {
            FlowBlueprint loaded = loadBlueprintFromJson(s);
            if (loaded != null) {
                log.info("[FlowEngine] Loaded dynamic blueprint for: {}", subCategoryName);
                return loaded;
            }
            
            // Fallback to static mapping
            log.warn("[FlowEngine] No JSON blueprint for '{}', falling back to static mapping", s);
            String cat = subCategoryName.toLowerCase();
            if (cat.contains("dental") || cat.contains("skin") || cat.contains("physio") || cat.contains("homeopathy")) {
                return ENT_APPOINTMENT;
            }
            if (cat.contains("salon") || cat.contains("makeup") || cat.contains("event")) {
                return ENT_BOOKING;
            }
            if (cat.contains("real estate") || cat.contains("auto") || cat.contains("solar") || cat.contains("design")) {
                return ENT_LEAD_CAPTURE;
            }
            return genericEnquiry();
        });
    }

    private FlowBlueprint loadBlueprintFromJson(String slug) {
        String path = "/flows/" + slug + ".json";
        try (InputStream is = getClass().getResourceAsStream(path)) {
            if (is == null) return null;
            
            // Now the entire file is the blueprint
            return objectMapper.readValue(is, FlowBlueprint.class);
        } catch (Exception e) {
            log.error("[FlowEngine] Error loading blueprint for {}: {}", slug, e.getMessage());
            return null;
        }
    }

    private String toSlug(String name) {
        if (name == null) return "generic";
        return name.trim()
                .toLowerCase()
                .replaceAll("[/&]", " ")
                .replaceAll("[^a-z0-9\\s-]", "")
                .replaceAll("\\s+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
    }

    // ── Blueprints ──────────────────────────────────────────────────────────

    private static final FlowBlueprint ENT_APPOINTMENT = FlowBlueprint.builder()
            .flowType(FlowType.APPOINTMENT)
            .steps(List.of(
                    new FlowStep("service", "✨ Which service or treatment would you like to book?", true, true, List.of()),
                    new FlowStep("email", "\uD83D\uDCE7 Please provide your email address for appointment confirmation:", false, false, List.of())
            )).build();

    private static final FlowBlueprint ENT_BOOKING = FlowBlueprint.builder()
            .flowType(FlowType.BOOKING)
            .steps(List.of(
                    new FlowStep("service", "✨ Which service would you like to book?", true, true, List.of()),
                    new FlowStep("email", "📩 Great! What is your email address?", false, false, List.of())
            )).build();

    private static final FlowBlueprint ENT_LEAD_CAPTURE = FlowBlueprint.builder()
            .flowType(FlowType.LEAD_CAPTURE)
            .steps(List.of(
                    new FlowStep("service", "✨ Which service or product are you interested in?", true, true, List.of()),
                    new FlowStep("email", "📧 What is your best contact email for a detailed quote?", false, false, List.of())
            )).build();

    private static FlowBlueprint genericEnquiry() {
        return FlowBlueprint.builder()
                .flowType(FlowType.ENQUIRY)
                .steps(List.of(
                        new FlowStep("email", "📧 Please provide your email:", false, false, List.of()),
                        new FlowStep("query", "\uD83D\uDCDD How can we help you today?", false, false, List.of())
                )).build();
    }

    // ── Helper Classes ──────────────────────────────────────────────────────

    @Getter @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class FlowBlueprint {
        private FlowType flowType;
        private List<FlowStep> steps;
    }

    @Getter @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class FlowStep {
        private String dataKey;
        private String question;
        private boolean usesButtons;
        private boolean dynamicSource;
        @Builder.Default
        private List<String> options = List.of();
    }

    public void clearCache() {
        labelCache.clear();
    }
}
