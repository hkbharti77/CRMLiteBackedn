package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.dto.MenuCardDTO;
import com.chatcrmlite.backend.dto.MenuCardRequest;
import com.chatcrmlite.backend.models.CustomMenuCard;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.CustomMenuCardRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.NicheThemeService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST controller for the tenant "Menu Builder" feature.
 *
 * All endpoints are protected — the user must be authenticated.
 *
 * GET  /api/v1/tenant/menu-builder       → Fetch all custom cards for the logged-in tenant
 * POST /api/v1/tenant/menu-builder       → Bulk-replace all custom cards for the logged-in tenant
 * DELETE /api/v1/tenant/menu-builder     → Remove all custom cards (revert to niche defaults)
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/tenant/menu-builder")
public class MenuBuilderController {

    @Autowired
    private CustomMenuCardRepository menuCardRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private NicheThemeService nicheThemeService;

    // ── GET — fetch current custom cards ─────────────────────────────────────

    @GetMapping
    public ResponseEntity<Map<String, Object>> getMenuCards(
            @AuthenticationPrincipal String email) {

        User user = userRepository.findByEmailWithTenant(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
        Tenant tenant = user.getTenant();
        if (tenant == null) {
            return ResponseEntity.badRequest().build();
        }

        List<MenuCardDTO> cards = menuCardRepository
                .findByTenantAndSectionOrderByDisplayOrderAsc(tenant, "SERVICES")
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());

        Map<String, Object> response = new java.util.HashMap<>();
        if (cards.isEmpty()) {
            String slug = (user.getBusinessSubType() != null && !user.getBusinessSubType().isBlank()) 
                            ? user.getBusinessSubType() 
                            : "retail";
            cards = nicheThemeService.getDefaultServiceCards(slug);
            response.put("isCustom", false);
        } else {
            response.put("isCustom", true);
        }
        
        response.put("cards", cards);
        return ResponseEntity.ok(response);
    }

    // ── POST — bulk-replace cards ─────────────────────────────────────────────

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

        if (requests.size() > 10) {
            return ResponseEntity.badRequest().body(Map.of("error", "Maximum 10 cards allowed per tenant"));
        }

        // Delete existing and save new cards
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

    // ── DELETE — revert to niche defaults ────────────────────────────────────

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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private MenuCardDTO toDTO(CustomMenuCard entity) {
        return new MenuCardDTO(
                entity.getTitle(),
                entity.getSubtitle(),
                entity.getIcon(),
                entity.getActionType(),
                entity.getActionPayload()
        );
    }

    private CustomMenuCard toEntity(MenuCardRequest req, Tenant tenant) {
        CustomMenuCard card = new CustomMenuCard();
        card.setTenant(tenant);
        card.setSection(req.getSection() != null ? req.getSection().toUpperCase() : "SERVICES");
        card.setTitle(req.getTitle());
        card.setSubtitle(req.getSubtitle());
        card.setIcon(req.getIcon() != null ? req.getIcon() : "briefcase");
        card.setActionType(req.getActionType().toUpperCase());
        card.setActionPayload(req.getActionPayload() != null ? req.getActionPayload() : "");
        card.setDisplayOrder(req.getDisplayOrder() != null ? req.getDisplayOrder() : 0);
        return card;
    }
}
