package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.dto.MenuBuilderCardDTO;
import com.chatcrmlite.backend.dto.MenuCardDTO;
import com.chatcrmlite.backend.dto.MenuCardRequest;
import com.chatcrmlite.backend.models.CustomMenuCard;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.CustomMenuCardRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.NicheThemeService;
import com.chatcrmlite.backend.util.MenuActionTypes;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * REST controller for the tenant "Menu Builder" feature.
 *
 * GET  /api/v1/tenant/menu-builder   → Fetch SERVICES + RESOURCES cards
 * POST /api/v1/tenant/menu-builder   → Bulk-replace all custom cards (both sections)
 * DELETE /api/v1/tenant/menu-builder → Remove all custom cards (revert to defaults)
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/tenant/menu-builder")
public class MenuBuilderController {

    private static final Set<String> ALLOWED_SECTIONS = Set.of("SERVICES", "RESOURCES");
    private static final int MAX_CARDS_PER_SECTION = 10;

    @Autowired
    private CustomMenuCardRepository menuCardRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private NicheThemeService nicheThemeService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getMenuCards(
            @AuthenticationPrincipal String email) {

        User user = userRepository.findByEmailWithTenant(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
        Tenant tenant = user.getTenant();
        if (tenant == null) {
            return ResponseEntity.badRequest().build();
        }

        String slug = (user.getBusinessSubType() != null && !user.getBusinessSubType().isBlank())
                ? user.getBusinessSubType()
                : "retail";

        boolean hasCustom = menuCardRepository.existsByTenant(tenant);
        List<MenuBuilderCardDTO> servicesCards;
        List<MenuBuilderCardDTO> resourcesCards;

        if (!hasCustom) {
            servicesCards = fromDefaults(nicheThemeService.getDefaultServiceCards(slug), "SERVICES");
            resourcesCards = fromDefaults(nicheThemeService.getDefaultResourceCards(), "RESOURCES");
        } else {
            servicesCards = loadSectionCards(tenant, "SERVICES");
            resourcesCards = loadSectionCards(tenant, "RESOURCES");
        }

        List<MenuBuilderCardDTO> allCards = new ArrayList<>(servicesCards);
        allCards.addAll(resourcesCards);

        Map<String, Object> response = new HashMap<>();
        response.put("isCustom", hasCustom);
        response.put("cards", allCards);
        response.put("servicesCards", servicesCards);
        response.put("resourcesCards", resourcesCards);
        return ResponseEntity.ok(response);
    }

    @Transactional
    @PostMapping
    public ResponseEntity<Map<String, Object>> saveMenuCards(
            @AuthenticationPrincipal String email,
            @Valid @RequestBody List<MenuCardRequest> requests) {

        User user = userRepository.findByEmailWithTenant(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
        Tenant tenant = user.getTenant();
        if (tenant == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Tenant not found"));
        }

        if (requests == null || requests.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "At least one card is required"));
        }

        long servicesCount = requests.stream()
                .filter(r -> "SERVICES".equals(normalizeSection(r.getSection())))
                .count();
        long resourcesCount = requests.stream()
                .filter(r -> "RESOURCES".equals(normalizeSection(r.getSection())))
                .count();

        if (servicesCount > MAX_CARDS_PER_SECTION || resourcesCount > MAX_CARDS_PER_SECTION) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Maximum " + MAX_CARDS_PER_SECTION + " cards allowed per section"));
        }

        for (MenuCardRequest req : requests) {
            String section = normalizeSection(req.getSection());
            if (!ALLOWED_SECTIONS.contains(section)) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Invalid section: " + req.getSection() + ". Use SERVICES or RESOURCES."));
            }
        }

        menuCardRepository.deleteByTenant(tenant);

        List<CustomMenuCard> newCards = requests.stream()
                .map(req -> toEntity(req, tenant))
                .collect(Collectors.toList());

        menuCardRepository.saveAll(newCards);

        log.info("[MenuBuilder] Tenant {} saved {} custom menu cards", tenant.getId(), newCards.size());

        return ResponseEntity.ok(Map.of(
                "message", "Menu cards saved successfully",
                "count", newCards.size()
        ));
    }

    @Transactional
    @DeleteMapping
    public ResponseEntity<Map<String, String>> resetMenuCards(
            @AuthenticationPrincipal String email) {

        User user = userRepository.findByEmailWithTenant(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
        Tenant tenant = user.getTenant();
        if (tenant == null) {
            return ResponseEntity.badRequest().build();
        }

        menuCardRepository.deleteByTenant(tenant);
        log.info("[MenuBuilder] Tenant {} reset to niche defaults", tenant.getId());

        return ResponseEntity.ok(Map.of("message", "Custom cards removed. Niche defaults will be used."));
    }

    private List<MenuBuilderCardDTO> loadSectionCards(Tenant tenant, String section) {
        return menuCardRepository
                .findByTenantAndSectionOrderByDisplayOrderAsc(tenant, section)
                .stream()
                .map(c -> toBuilderDTO(c, section))
                .collect(Collectors.toList());
    }

    private List<MenuBuilderCardDTO> fromDefaults(List<MenuCardDTO> defaults, String section) {
        List<MenuBuilderCardDTO> result = new ArrayList<>();
        for (int i = 0; i < defaults.size(); i++) {
            MenuCardDTO d = defaults.get(i);
            result.add(new MenuBuilderCardDTO(
                    section,
                    d.getTitle(),
                    d.getSubtitle(),
                    d.getIcon(),
                    MenuActionTypes.normalize(d.getActionType()),
                    d.getActionPayload(),
                    i
            ));
        }
        return result;
    }

    private MenuBuilderCardDTO toBuilderDTO(CustomMenuCard entity, String section) {
        return new MenuBuilderCardDTO(
                section,
                entity.getTitle(),
                entity.getSubtitle(),
                entity.getIcon(),
                MenuActionTypes.normalize(entity.getActionType()),
                entity.getActionPayload(),
                entity.getDisplayOrder()
        );
    }

    private CustomMenuCard toEntity(MenuCardRequest req, Tenant tenant) {
        CustomMenuCard card = new CustomMenuCard();
        card.setTenant(tenant);
        card.setSection(normalizeSection(req.getSection()));
        card.setTitle(req.getTitle());
        card.setSubtitle(req.getSubtitle());
        card.setIcon(req.getIcon() != null ? req.getIcon() : "briefcase");
        card.setActionType(MenuActionTypes.normalize(req.getActionType()));
        card.setActionPayload(req.getActionPayload() != null ? req.getActionPayload() : "");
        card.setDisplayOrder(req.getDisplayOrder() != null ? req.getDisplayOrder() : 0);
        return card;
    }

    private String normalizeSection(String section) {
        if (section == null || section.isBlank()) {
            return "SERVICES";
        }
        return section.trim().toUpperCase();
    }
}
