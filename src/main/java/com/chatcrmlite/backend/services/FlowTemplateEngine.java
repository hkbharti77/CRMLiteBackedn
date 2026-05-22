package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.models.BusinessSubCategory;
import com.chatcrmlite.backend.models.ConversationState.FlowType;
import com.chatcrmlite.backend.repositories.BusinessSubCategoryRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class FlowTemplateEngine {
    private static final Logger log = LoggerFactory.getLogger(FlowTemplateEngine.class);

    @Autowired
    private BusinessSubCategoryRepository subCategoryRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private final Map<String, LabelPair> labelCache = new ConcurrentHashMap<>();
    private final Map<String, FlowBlueprint> blueprintCache = new ConcurrentHashMap<>();

    private record LabelPair(String trigger, String services) {}

    public FlowTemplateEngine() {}

    public String getTriggerButtonLabel(String subCategoryName) {
        return getLabels(subCategoryName).trigger();
    }

    public String getServicesLabel(String subCategoryName) {
        return getLabels(subCategoryName).services();
    }

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

    public FlowBlueprint getBlueprint(String subCategoryName) {
        if (subCategoryName == null || subCategoryName.isBlank()) return genericEnquiry();
        
        String slug = toSlug(subCategoryName);
        
        return blueprintCache.computeIfAbsent(slug, s -> {
            FlowBlueprint loaded = loadBlueprintFromJson(s);
            if (loaded != null) {
                log.info("[FlowEngine] Loaded dynamic blueprint for: {}", subCategoryName);
                return loaded;
            }
            
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

    private static final FlowBlueprint ENT_APPOINTMENT = FlowBlueprint.builder()
            .flowType(FlowType.APPOINTMENT)
            .steps(List.of(
                    new FlowStep("service", "✨ Which service or treatment would you like to book?", true, true, false, List.of()),
                    new FlowStep("email", "📧 Please provide your email address for appointment confirmation:", false, false, false, List.of())
            )).build();

    private static final FlowBlueprint ENT_BOOKING = FlowBlueprint.builder()
            .flowType(FlowType.BOOKING)
            .steps(List.of(
                    new FlowStep("service", "✨ Which service would you like to book?", true, true, false, List.of()),
                    new FlowStep("email", "📩 Great! What is your email address?", false, false, false, List.of())
            )).build();

    private static final FlowBlueprint ENT_LEAD_CAPTURE = FlowBlueprint.builder()
            .flowType(FlowType.LEAD_CAPTURE)
            .steps(List.of(
                    new FlowStep("service", "✨ Which service or product are you interested in?", true, true, false, List.of()),
                    new FlowStep("email", "📧 What is your best contact email for a detailed quote?", false, false, false, List.of())
            )).build();

    private static FlowBlueprint genericEnquiry() {
        return FlowBlueprint.builder()
                .flowType(FlowType.ENQUIRY)
                .steps(List.of(
                        new FlowStep("email", "📧 Please provide your email:", false, false, false, List.of()),
                        new FlowStep("query", "📝 How can we help you today?", false, false, false, List.of())
                )).build();
    }

    public static class FlowBlueprint {
        private FlowType flowType;
        private List<FlowStep> steps;

        public FlowBlueprint() {}

        public FlowBlueprint(FlowType flowType, List<FlowStep> steps) {
            this.flowType = flowType;
            this.steps = (steps != null) ? steps : new ArrayList<>();
        }

        public FlowType getFlowType() { return flowType; }
        public void setFlowType(FlowType flowType) { this.flowType = flowType; }
        public List<FlowStep> getSteps() { return steps; }
        public void setSteps(List<FlowStep> steps) { this.steps = steps; }

        public static FlowBlueprintBuilder builder() { return new FlowBlueprintBuilder(); }

        public static class FlowBlueprintBuilder {
            private FlowType flowType;
            private List<FlowStep> steps;

            public FlowBlueprintBuilder flowType(FlowType flowType) { this.flowType = flowType; return this; }
            public FlowBlueprintBuilder steps(List<FlowStep> steps) { this.steps = steps; return this; }

            public FlowBlueprint build() {
                return new FlowBlueprint(flowType, steps);
            }
        }
    }

    public static class FlowStep {
        private String dataKey;
        private String question;
        private boolean usesButtons;
        private boolean dynamicSource;
        private boolean usesList;
        private List<String> options = new ArrayList<>();

        public FlowStep() {}

        public FlowStep(String dataKey, String question, boolean usesButtons, boolean dynamicSource, boolean usesList, List<String> options) {
            this.dataKey = dataKey;
            this.question = question;
            this.usesButtons = usesButtons;
            this.dynamicSource = dynamicSource;
            this.usesList = usesList;
            this.options = (options != null) ? options : new ArrayList<>();
        }

        public String getDataKey() { return dataKey; }
        public void setDataKey(String dataKey) { this.dataKey = dataKey; }
        public String getQuestion() { return question; }
        public void setQuestion(String question) { this.question = question; }
        public boolean isUsesButtons() { return usesButtons; }
        public void setUsesButtons(boolean usesButtons) { this.usesButtons = usesButtons; }
        public boolean isDynamicSource() { return dynamicSource; }
        public void setDynamicSource(boolean dynamicSource) { this.dynamicSource = dynamicSource; }
        public boolean isUsesList() { return usesList; }
        public void setUsesList(boolean usesList) { this.usesList = usesList; }
        public List<String> getOptions() { return options; }
        public void setOptions(List<String> options) { this.options = options; }

        public static FlowStepBuilder builder() { return new FlowStepBuilder(); }

        public static class FlowStepBuilder {
            private String dataKey;
            private String question;
            private boolean usesButtons;
            private boolean dynamicSource;
            private boolean usesList;
            private List<String> options;

            public FlowStepBuilder dataKey(String dataKey) { this.dataKey = dataKey; return this; }
            public FlowStepBuilder question(String question) { this.question = question; return this; }
            public FlowStepBuilder usesButtons(boolean usesButtons) { this.usesButtons = usesButtons; return this; }
            public FlowStepBuilder dynamicSource(boolean dynamicSource) { this.dynamicSource = dynamicSource; return this; }
            public FlowStepBuilder usesList(boolean usesList) { this.usesList = usesList; return this; }
            public FlowStepBuilder options(List<String> options) { this.options = options; return this; }

            public FlowStep build() {
                return new FlowStep(dataKey, question, usesButtons, dynamicSource, usesList, options);
            }
        }
    }

    public void clearCache() {
        blueprintCache.clear();
        labelCache.clear();
        log.info("[FlowEngine] Full cache cleared — all blueprints and labels will reload on next request");
    }

    public void evictBlueprint(String subCategoryName) {
        if (subCategoryName == null || subCategoryName.isBlank()) return;
        String slug = toSlug(subCategoryName);
        FlowBlueprint removed = blueprintCache.remove(slug);
        labelCache.remove(subCategoryName);
        if (removed != null) {
            log.info("[FlowEngine] Evicted blueprint cache for slug='{}' (sub-category='{}')", slug, subCategoryName);
        } else {
            log.info("[FlowEngine] No cached blueprint found for slug='{}' — nothing to evict", slug);
        }
    }

    public java.util.Set<String> getCachedSlugs() {
        return java.util.Collections.unmodifiableSet(blueprintCache.keySet());
    }
}
