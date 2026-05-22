package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.dto.SupportFormConfigDTO;
import com.chatcrmlite.backend.models.SupportFormConfig;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.SupportFormConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/support-form-config")
@Tag(name = "Support Form Configuration", description = "Manage support form settings and categories")
public class SupportFormConfigController {
    private static final Logger log = LoggerFactory.getLogger(SupportFormConfigController.class);

    @Autowired
    private SupportFormConfigService configService;

    @Autowired
    private UserRepository userRepository;

    private User getAuthenticatedUser() {
        String email = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    @GetMapping
    @Operation(summary = "Get support form configuration", 
               description = "Retrieve the current support form configuration including categories")
    public ResponseEntity<SupportFormConfigResponse> getConfig() {
        User user = getAuthenticatedUser();
        SupportFormConfig config = configService.getOrCreateConfig(user);
        
        SupportFormConfigResponse response = SupportFormConfigResponse.builder()
                .id(config.getId())
                .formTitle(config.getFormTitle())
                .formDescription(config.getFormDescription())
                .successMessage(config.getSuccessMessage())
                .phoneRequired(config.isPhoneRequired())
                .categoryRequired(config.isCategoryRequired())
                .categories(parseCategories(config.getCategories()))
                .primaryColor(config.getPrimaryColor())
                .logoUrl(config.getLogoUrl())
                .rateLimitEnabled(config.isRateLimitEnabled())
                .duplicateDetectionEnabled(config.isDuplicateDetectionEnabled())
                .defaultPriority(config.getDefaultPriority())
                .enabled(config.isEnabled())
                .build();

        return ResponseEntity.ok(response);
    }

    @PutMapping
    @Operation(summary = "Update support form configuration", 
               description = "Update support form settings including custom categories")
    public ResponseEntity<SupportFormConfigResponse> updateConfig(
            @Valid @RequestBody UpdateSupportFormConfigRequest request) {
        
        User user = getAuthenticatedUser();
        
        SupportFormConfig updates = SupportFormConfig.builder()
                .formTitle(request.getFormTitle())
                .formDescription(request.getFormDescription())
                .successMessage(request.getSuccessMessage())
                .phoneRequired(request.isPhoneRequired())
                .categoryRequired(request.isCategoryRequired())
                .categories(String.join(",", request.getCategories()))
                .primaryColor(request.getPrimaryColor())
                .logoUrl(request.getLogoUrl())
                .rateLimitEnabled(request.isRateLimitEnabled())
                .duplicateDetectionEnabled(request.isDuplicateDetectionEnabled())
                .defaultPriority(request.getDefaultPriority())
                .enabled(request.isEnabled())
                .build();

        SupportFormConfig saved = configService.updateConfig(user, updates);
        
        SupportFormConfigResponse response = SupportFormConfigResponse.builder()
                .id(saved.getId())
                .formTitle(saved.getFormTitle())
                .formDescription(saved.getFormDescription())
                .successMessage(saved.getSuccessMessage())
                .phoneRequired(saved.isPhoneRequired())
                .categoryRequired(saved.isCategoryRequired())
                .categories(parseCategories(saved.getCategories()))
                .primaryColor(saved.getPrimaryColor())
                .logoUrl(saved.getLogoUrl())
                .rateLimitEnabled(saved.isRateLimitEnabled())
                .duplicateDetectionEnabled(saved.isDuplicateDetectionEnabled())
                .defaultPriority(saved.getDefaultPriority())
                .enabled(saved.isEnabled())
                .build();

        log.info("[SupportFormConfig] Updated configuration for user={}", user.getId());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/category-templates")
    @Operation(summary = "Get category templates", 
               description = "Get predefined category templates relevant to user's business type")
    public ResponseEntity<Map<String, List<String>>> getCategoryTemplates() {
        User user = getAuthenticatedUser();
        
        Map<String, List<String>> allTemplates = new java.util.HashMap<>();
        allTemplates.put("General Business", Arrays.asList("General", "Technical", "Billing", "Account Issue", "Feature Request"));
        allTemplates.put("Healthcare", Arrays.asList("Appointment Issue", "Treatment Query", "Billing & Insurance", "Emergency", "General"));
        allTemplates.put("Beauty & Wellness", Arrays.asList("Booking Issue", "Service Query", "Pricing", "Product Query", "Complaint", "General"));
        allTemplates.put("Education", Arrays.asList("Session Booking", "Subject Query", "Pricing", "Study Material", "Exam Prep", "General"));
        allTemplates.put("Real Estate", Arrays.asList("Property Query", "Site Visit", "Documentation", "Pricing", "Legal Query", "General"));
        allTemplates.put("Travel & Tourism", Arrays.asList("Booking Issue", "Itinerary Query", "Pricing", "Cancellation", "Visa Query", "General"));
        allTemplates.put("Technology", Arrays.asList("Technical Issue", "Bug Report", "Feature Request", "Account Issue", "Billing", "General"));
        allTemplates.put("E-commerce", Arrays.asList("Order Issue", "Product Query", "Shipping", "Returns", "Payment Issue", "General"));
        allTemplates.put("Fitness & Gym", Arrays.asList("Membership Issue", "Class Booking", "Equipment Issue", "Trainer Query", "Billing", "General"));
        allTemplates.put("Restaurant & Food", Arrays.asList("Order Issue", "Reservation", "Menu Query", "Delivery Issue", "Complaint", "General"));
        allTemplates.put("Automotive", Arrays.asList("Service Booking", "Parts Query", "Warranty Issue", "Pricing", "Technical Support", "General"));
        allTemplates.put("Legal Services", Arrays.asList("Consultation Request", "Case Query", "Document Issue", "Billing", "Appointment", "General"));
        
        Map<String, List<String>> filteredTemplates = getRelevantTemplates(user, allTemplates);
        
        log.info("[SupportFormConfig] Provided {} relevant templates for business type: {}", 
                filteredTemplates.size(), user.getBusinessType());
        return ResponseEntity.ok(filteredTemplates);
    }
    
    private Map<String, List<String>> getRelevantTemplates(User user, Map<String, List<String>> allTemplates) {
        String businessType = user.getBusinessType();
        String businessSubType = user.getBusinessSubType();
        
        Map<String, List<String>> relevantTemplates = new java.util.LinkedHashMap<>();
        
        relevantTemplates.put("General Business", allTemplates.get("General Business"));
        
        if (businessType == null || businessType.isBlank()) {
            return relevantTemplates;
        }
        
        switch (businessType.toLowerCase()) {
            case "healthcare":
            case "medical":
            case "dental":
            case "clinic":
                relevantTemplates.put("Healthcare", allTemplates.get("Healthcare"));
                break;
                
            case "beauty":
            case "wellness":
            case "spa":
            case "salon":
                relevantTemplates.put("Beauty & Wellness", allTemplates.get("Beauty & Wellness"));
                break;
                
            case "education":
            case "training":
            case "coaching":
            case "tutoring":
                relevantTemplates.put("Education", allTemplates.get("Education"));
                break;
                
            case "real estate":
            case "property":
            case "realty":
                relevantTemplates.put("Real Estate", allTemplates.get("Real Estate"));
                break;
                
            case "travel":
            case "tourism":
            case "tour":
            case "hospitality":
                relevantTemplates.put("Travel & Tourism", allTemplates.get("Travel & Tourism"));
                break;
                
            case "technology":
            case "software":
            case "it":
            case "tech":
                relevantTemplates.put("Technology", allTemplates.get("Technology"));
                break;
                
            case "ecommerce":
            case "e-commerce":
            case "retail":
            case "online store":
                relevantTemplates.put("E-commerce", allTemplates.get("E-commerce"));
                break;
                
            case "fitness":
            case "gym":
            case "sports":
            case "health club":
                relevantTemplates.put("Fitness & Gym", allTemplates.get("Fitness & Gym"));
                break;
                
            case "restaurant":
            case "food":
            case "cafe":
            case "catering":
                relevantTemplates.put("Restaurant & Food", allTemplates.get("Restaurant & Food"));
                break;
                
            case "automotive":
            case "car":
            case "vehicle":
            case "auto":
                relevantTemplates.put("Automotive", allTemplates.get("Automotive"));
                break;
                
            case "legal":
            case "law":
            case "attorney":
            case "lawyer":
                relevantTemplates.put("Legal Services", allTemplates.get("Legal Services"));
                break;
        }
        
        if (businessSubType != null && !businessSubType.isBlank()) {
            addSubTypeTemplates(businessSubType, relevantTemplates, allTemplates);
        }
        
        return relevantTemplates;
    }
    
    private void addSubTypeTemplates(String subType, Map<String, List<String>> relevantTemplates, 
                                   Map<String, List<String>> allTemplates) {
        String lowerSubType = subType.toLowerCase();
        
        if (lowerSubType.contains("dental") && !relevantTemplates.containsKey("Healthcare")) {
            relevantTemplates.put("Healthcare", allTemplates.get("Healthcare"));
        }
        
        if (lowerSubType.contains("spa") && !relevantTemplates.containsKey("Beauty & Wellness")) {
            relevantTemplates.put("Beauty & Wellness", allTemplates.get("Beauty & Wellness"));
        }
        
        if (lowerSubType.contains("hotel") && !relevantTemplates.containsKey("Travel & Tourism")) {
            relevantTemplates.put("Travel & Tourism", allTemplates.get("Travel & Tourism"));
        }
        
        if (lowerSubType.contains("online") && !relevantTemplates.containsKey("E-commerce")) {
            relevantTemplates.put("E-commerce", allTemplates.get("E-commerce"));
        }
    }

    @PostMapping("/reset")
    @Operation(summary = "Reset to default configuration", 
               description = "Reset support form configuration to default values")
    public ResponseEntity<SupportFormConfigResponse> resetConfig() {
        User user = getAuthenticatedUser();
        
        SupportFormConfig defaultConfig = SupportFormConfig.builder()
                .formTitle("Get Support")
                .formDescription("Need help? Submit your request and we'll get back to you soon.")
                .successMessage("✅ Thank you for contacting support! We've received your request and will get back to you shortly.")
                .phoneRequired(false)
                .categoryRequired(false)
                .categories("General,Technical,Billing,Account Issue,Feature Request")
                .primaryColor("#667eea")
                .rateLimitEnabled(true)
                .duplicateDetectionEnabled(true)
                .enabled(true)
                .build();

        SupportFormConfig saved = configService.updateConfig(user, defaultConfig);
        
        SupportFormConfigResponse response = SupportFormConfigResponse.builder()
                .id(saved.getId())
                .formTitle(saved.getFormTitle())
                .formDescription(saved.getFormDescription())
                .successMessage(saved.getSuccessMessage())
                .phoneRequired(saved.isPhoneRequired())
                .categoryRequired(saved.isCategoryRequired())
                .categories(parseCategories(saved.getCategories()))
                .primaryColor(saved.getPrimaryColor())
                .logoUrl(saved.getLogoUrl())
                .rateLimitEnabled(saved.isRateLimitEnabled())
                .duplicateDetectionEnabled(saved.isDuplicateDetectionEnabled())
                .defaultPriority(saved.getDefaultPriority())
                .enabled(saved.isEnabled())
                .build();

        log.info("[SupportFormConfig] Reset configuration to defaults for user={}", user.getId());
        return ResponseEntity.ok(response);
    }

    private List<String> parseCategories(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of("General", "Technical", "Billing");
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }

    public static class SupportFormConfigResponse {
        private UUID id;
        private String formTitle;
        private String formDescription;
        private String successMessage;
        private boolean phoneRequired;
        private boolean categoryRequired;
        private List<String> categories;
        private String primaryColor;
        private String logoUrl;
        private boolean rateLimitEnabled;
        private boolean duplicateDetectionEnabled;
        private com.chatcrmlite.backend.models.Ticket.TicketPriority defaultPriority;
        private boolean enabled;

        public SupportFormConfigResponse() {}

        public SupportFormConfigResponse(UUID id, String formTitle, String formDescription, String successMessage, boolean phoneRequired, boolean categoryRequired, List<String> categories, String primaryColor, String logoUrl, boolean rateLimitEnabled, boolean duplicateDetectionEnabled, com.chatcrmlite.backend.models.Ticket.TicketPriority defaultPriority, boolean enabled) {
            this.id = id;
            this.formTitle = formTitle;
            this.formDescription = formDescription;
            this.successMessage = successMessage;
            this.phoneRequired = phoneRequired;
            this.categoryRequired = categoryRequired;
            this.categories = categories;
            this.primaryColor = primaryColor;
            this.logoUrl = logoUrl;
            this.rateLimitEnabled = rateLimitEnabled;
            this.duplicateDetectionEnabled = duplicateDetectionEnabled;
            this.defaultPriority = defaultPriority;
            this.enabled = enabled;
        }

        public UUID getId() { return id; }
        public void setId(UUID id) { this.id = id; }
        public String getFormTitle() { return formTitle; }
        public void setFormTitle(String formTitle) { this.formTitle = formTitle; }
        public String getFormDescription() { return formDescription; }
        public void setFormDescription(String formDescription) { this.formDescription = formDescription; }
        public String getSuccessMessage() { return successMessage; }
        public void setSuccessMessage(String successMessage) { this.successMessage = successMessage; }
        public boolean isPhoneRequired() { return phoneRequired; }
        public void setPhoneRequired(boolean phoneRequired) { this.phoneRequired = phoneRequired; }
        public boolean isCategoryRequired() { return categoryRequired; }
        public void setCategoryRequired(boolean categoryRequired) { this.categoryRequired = categoryRequired; }
        public List<String> getCategories() { return categories; }
        public void setCategories(List<String> categories) { this.categories = categories; }
        public String getPrimaryColor() { return primaryColor; }
        public void setPrimaryColor(String primaryColor) { this.primaryColor = primaryColor; }
        public String getLogoUrl() { return logoUrl; }
        public void setLogoUrl(String logoUrl) { this.logoUrl = logoUrl; }
        public boolean isRateLimitEnabled() { return rateLimitEnabled; }
        public void setRateLimitEnabled(boolean rateLimitEnabled) { this.rateLimitEnabled = rateLimitEnabled; }
        public boolean isDuplicateDetectionEnabled() { return duplicateDetectionEnabled; }
        public void setDuplicateDetectionEnabled(boolean duplicateDetectionEnabled) { this.duplicateDetectionEnabled = duplicateDetectionEnabled; }
        public com.chatcrmlite.backend.models.Ticket.TicketPriority getDefaultPriority() { return defaultPriority; }
        public void setDefaultPriority(com.chatcrmlite.backend.models.Ticket.TicketPriority defaultPriority) { this.defaultPriority = defaultPriority; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public static SupportFormConfigResponseBuilder builder() { return new SupportFormConfigResponseBuilder(); }

        public static class SupportFormConfigResponseBuilder {
            private UUID id;
            private String formTitle;
            private String formDescription;
            private String successMessage;
            private boolean phoneRequired;
            private boolean categoryRequired;
            private List<String> categories;
            private String primaryColor;
            private String logoUrl;
            private boolean rateLimitEnabled;
            private boolean duplicateDetectionEnabled;
            private com.chatcrmlite.backend.models.Ticket.TicketPriority defaultPriority;
            private boolean enabled;

            public SupportFormConfigResponseBuilder id(UUID id) { this.id = id; return this; }
            public SupportFormConfigResponseBuilder formTitle(String formTitle) { this.formTitle = formTitle; return this; }
            public SupportFormConfigResponseBuilder formDescription(String formDescription) { this.formDescription = formDescription; return this; }
            public SupportFormConfigResponseBuilder successMessage(String successMessage) { this.successMessage = successMessage; return this; }
            public SupportFormConfigResponseBuilder phoneRequired(boolean phoneRequired) { this.phoneRequired = phoneRequired; return this; }
            public SupportFormConfigResponseBuilder categoryRequired(boolean categoryRequired) { this.categoryRequired = categoryRequired; return this; }
            public SupportFormConfigResponseBuilder categories(List<String> categories) { this.categories = categories; return this; }
            public SupportFormConfigResponseBuilder primaryColor(String primaryColor) { this.primaryColor = primaryColor; return this; }
            public SupportFormConfigResponseBuilder logoUrl(String logoUrl) { this.logoUrl = logoUrl; return this; }
            public SupportFormConfigResponseBuilder rateLimitEnabled(boolean rateLimitEnabled) { this.rateLimitEnabled = rateLimitEnabled; return this; }
            public SupportFormConfigResponseBuilder duplicateDetectionEnabled(boolean duplicateDetectionEnabled) { this.duplicateDetectionEnabled = duplicateDetectionEnabled; return this; }
            public SupportFormConfigResponseBuilder defaultPriority(com.chatcrmlite.backend.models.Ticket.TicketPriority defaultPriority) { this.defaultPriority = defaultPriority; return this; }
            public SupportFormConfigResponseBuilder enabled(boolean enabled) { this.enabled = enabled; return this; }

            public SupportFormConfigResponse build() {
                return new SupportFormConfigResponse(id, formTitle, formDescription, successMessage, phoneRequired, categoryRequired, categories, primaryColor, logoUrl, rateLimitEnabled, duplicateDetectionEnabled, defaultPriority, enabled);
            }
        }
    }

    public static class UpdateSupportFormConfigRequest {
        private String formTitle;
        private String formDescription;
        private String successMessage;
        private boolean phoneRequired;
        private boolean categoryRequired;
        private List<String> categories;
        private String primaryColor;
        private String logoUrl;
        private boolean rateLimitEnabled;
        private boolean duplicateDetectionEnabled;
        private com.chatcrmlite.backend.models.Ticket.TicketPriority defaultPriority;
        private boolean enabled;

        public UpdateSupportFormConfigRequest() {}

        public String getFormTitle() { return formTitle; }
        public void setFormTitle(String formTitle) { this.formTitle = formTitle; }
        public String getFormDescription() { return formDescription; }
        public void setFormDescription(String formDescription) { this.formDescription = formDescription; }
        public String getSuccessMessage() { return successMessage; }
        public void setSuccessMessage(String successMessage) { this.successMessage = successMessage; }
        public boolean isPhoneRequired() { return phoneRequired; }
        public void setPhoneRequired(boolean phoneRequired) { this.phoneRequired = phoneRequired; }
        public boolean isCategoryRequired() { return categoryRequired; }
        public void setCategoryRequired(boolean categoryRequired) { this.categoryRequired = categoryRequired; }
        public List<String> getCategories() { return categories; }
        public void setCategories(List<String> categories) { this.categories = categories; }
        public String getPrimaryColor() { return primaryColor; }
        public void setPrimaryColor(String primaryColor) { this.primaryColor = primaryColor; }
        public String getLogoUrl() { return logoUrl; }
        public void setLogoUrl(String logoUrl) { this.logoUrl = logoUrl; }
        public boolean isRateLimitEnabled() { return rateLimitEnabled; }
        public void setRateLimitEnabled(boolean rateLimitEnabled) { this.rateLimitEnabled = rateLimitEnabled; }
        public boolean isDuplicateDetectionEnabled() { return duplicateDetectionEnabled; }
        public void setDuplicateDetectionEnabled(boolean duplicateDetectionEnabled) { this.duplicateDetectionEnabled = duplicateDetectionEnabled; }
        public com.chatcrmlite.backend.models.Ticket.TicketPriority getDefaultPriority() { return defaultPriority; }
        public void setDefaultPriority(com.chatcrmlite.backend.models.Ticket.TicketPriority defaultPriority) { this.defaultPriority = defaultPriority; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}