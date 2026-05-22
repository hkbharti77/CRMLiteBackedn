package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.dto.SupportFormConfigDTO;
import com.chatcrmlite.backend.models.SupportFormConfig;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.SupportFormConfigRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service for managing support form configurations.
 *
 * When a business has no saved config, a default is auto-created with
 * niche-specific categories seeded from NICHE_DEFAULT_CATEGORIES.
 * The business owner can then customize these from the CRM dashboard.
 */
@Slf4j
@Service
public class SupportFormConfigService {

    @Autowired
    private SupportFormConfigRepository configRepository;

    // ── Niche-default categories ───────────────────────────────────────────
    // Keyed by FlowTriggerEngine slug. Used only on first-time config creation.
    // Business owners can override these from the dashboard at any time.
    private static final Map<String, String> NICHE_DEFAULT_CATEGORIES = Map.ofEntries(
        Map.entry("dental-clinics",
            "Appointment Issue,Treatment Query,Billing & Insurance,Emergency / Pain,X-Ray Query,General"),
        Map.entry("premium-salons-hair-clinics",
            "Booking Issue,Service Query,Pricing,Product Query,Complaint,General"),
        Map.entry("skin-aesthetic-clinics",
            "Appointment Issue,Treatment Query,Pricing,Side Effects,Billing,General"),
        Map.entry("homeopathy-ayurveda-doctors",
            "Appointment Issue,Prescription Query,Medicine Query,Diet Advice,Billing,General"),
        Map.entry("physiotherapy-chiropractic-centers",
            "Appointment Issue,Treatment Plan,Exercise Query,Pain Query,Billing,General"),
        Map.entry("gym-personal-fitness-trainers",
            "Membership Issue,Schedule Query,Trainer Query,Diet Plan,Billing,General"),
        Map.entry("yoga-meditation-instructors",
            "Class Booking,Schedule Query,Pricing,Online Session,Certification,General"),
        Map.entry("independent-tutors",
            "Session Booking,Subject Query,Pricing,Study Material,Exam Prep,General"),
        Map.entry("music-art-classes",
            "Class Booking,Schedule Query,Fee Query,Instrument Query,Material Query,General"),
        Map.entry("event-wedding-planners",
            "Booking Issue,Package Query,Pricing,Vendor Query,Date Availability,General"),
        Map.entry("wedding-portrait-photographers",
            "Booking Issue,Package Query,Photo Delivery,Video Delivery,Pricing,General"),
        Map.entry("freelance-makeup-artists-mua",
            "Booking Issue,Service Query,Pricing,Product Query,Trial Session,General"),
        Map.entry("premium-tour-travel-operators",
            "Booking Issue,Itinerary Query,Pricing,Cancellation & Refund,Visa Query,General"),
        Map.entry("property-brokers",
            "Property Query,Site Visit Booking,Documentation,Pricing & Negotiation,Legal Query,General"),
        Map.entry("interior-designers-architects",
            "Project Query,Pricing & Quotation,Timeline,Material Query,Design Revision,General"),
        Map.entry("solar-panel-smart-home-installers",
            "Installation Query,Technical Issue,Warranty & AMC,Pricing,Subsidy Query,General"),
        Map.entry("auto-used-car-dealers",
            "Vehicle Query,Test Drive,Pricing & Finance,Documentation,Exchange Query,General"),
        Map.entry("insurance-agents",
            "Policy Query,Claim Issue,Premium Query,Renewal,Documentation,General"),
        Map.entry("career-study-abroad-counselors",
            "Counseling Session,University Query,Visa Query,Documentation,Scholarship Query,General"),
        Map.entry("freelance-web-graphic-designers",
            "Project Query,Revision Request,Delivery Query,Pricing,NDA & Contract,General"),
        Map.entry("generic",
            "Technical Issue,Billing,Account Issue,Feature Request,Bug Report,General")
    );

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Get or create config for a business.
     * On first creation, seeds niche-specific default categories.
     */
    @Transactional
    public SupportFormConfig getOrCreateConfig(User owner) {
        return configRepository.findByOwner(owner)
                .orElseGet(() -> {
                    String slug = FlowTriggerEngine.toSlug(owner.getBusinessSubType());
                    String defaultCategories = NICHE_DEFAULT_CATEGORIES.getOrDefault(
                            slug, NICHE_DEFAULT_CATEGORIES.get("generic"));

                    SupportFormConfig config = SupportFormConfig.builder()
                            .owner(owner)
                            .categories(defaultCategories)
                            .build();
                    SupportFormConfig saved = configRepository.save(config);
                    log.info("[SupportFormConfig] Created default config for owner={} niche={}",
                            owner.getId(), slug);
                    return saved;
                });
    }

    /**
     * Get public-facing config DTO — returned to the support form frontend.
     * Includes categories, branding, form text, and business info.
     */
    @Transactional(readOnly = true)
    public SupportFormConfigDTO getPublicConfig(UUID businessId, User owner) {
        SupportFormConfig config = getOrCreateConfig(owner);

        List<String> categories = parseCategories(config.getCategories());

        // Fall back to logo from User profile if not set in SupportFormConfig
        String logoUrl = config.getLogoUrl() != null ? config.getLogoUrl() : owner.getLogoUrl();

        return SupportFormConfigDTO.builder()
                .formTitle(config.getFormTitle())
                .formDescription(config.getFormDescription())
                .successMessage(config.getSuccessMessage())
                .phoneRequired(config.isPhoneRequired())
                .categoryRequired(config.isCategoryRequired())
                .categories(categories)
                .primaryColor(config.getPrimaryColor())
                .logoUrl(logoUrl)
                .businessId(businessId)
                .businessName(owner.getBusinessName())
                .enabled(config.isEnabled())
                .build();
    }

    /**
     * Update config from the CRM dashboard (authenticated).
     */
    @Transactional
    public SupportFormConfig updateConfig(User owner, SupportFormConfig updates) {
        SupportFormConfig config = getOrCreateConfig(owner);

        if (updates.getFormTitle() != null)       config.setFormTitle(updates.getFormTitle());
        if (updates.getFormDescription() != null) config.setFormDescription(updates.getFormDescription());
        if (updates.getSuccessMessage() != null)  config.setSuccessMessage(updates.getSuccessMessage());
        
        // FIX #12: Validate categories before saving
        if (updates.getCategories() != null) {
            validateCategories(updates.getCategories());
            config.setCategories(updates.getCategories());
        }
        
        if (updates.getPrimaryColor() != null)    config.setPrimaryColor(updates.getPrimaryColor());
        if (updates.getLogoUrl() != null)         config.setLogoUrl(updates.getLogoUrl());

        config.setPhoneRequired(updates.isPhoneRequired());
        config.setCategoryRequired(updates.isCategoryRequired());
        config.setRateLimitEnabled(updates.isRateLimitEnabled());
        config.setDuplicateDetectionEnabled(updates.isDuplicateDetectionEnabled());
        config.setDefaultPriority(updates.getDefaultPriority());
        config.setEnabled(updates.isEnabled());

        // Note: autoAssignAgent feature temporarily disabled due to schema mismatch
        // Will be re-enabled in future version with proper migration

        SupportFormConfig saved = configRepository.save(config);
        log.info("[SupportFormConfig] Updated config for owner={}", owner.getId());
        return saved;
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private List<String> parseCategories(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of("General", "Technical", "Billing");
        }
        // FIX #12: Validate category names against WhatsApp limits
        // FIX #23: Limit to 10 categories (WhatsApp list limit)
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .limit(10) // FIX #23: Enforce WhatsApp limit
                .map(category -> {
                    // FIX #12: Truncate to 24 chars (WhatsApp limit for list items)
                    if (category.length() > 24) {
                        log.warn("[SupportFormConfigService] Category name exceeds 24 chars, truncating: {}", category);
                        return category.substring(0, 24);
                    }
                    return category;
                })
                .toList();
    }

    /**
     * Validates category list before saving to ensure WhatsApp compatibility.
     * FIX #12: Enforces WhatsApp limits (max 10 items, max 24 chars each)
     */
    private void validateCategories(String categoriesRaw) {
        if (categoriesRaw == null || categoriesRaw.isBlank()) {
            return;
        }
        List<String> categories = parseCategories(categoriesRaw);
        if (categories.isEmpty()) {
            throw new IllegalArgumentException("At least one category is required");
        }
        if (categories.size() > 10) {
            throw new IllegalArgumentException("Maximum 10 categories allowed (WhatsApp limit)");
        }
        for (String cat : categories) {
            if (cat.length() > 24) {
                throw new IllegalArgumentException("Category name cannot exceed 24 characters: " + cat);
            }
        }
    }
}
