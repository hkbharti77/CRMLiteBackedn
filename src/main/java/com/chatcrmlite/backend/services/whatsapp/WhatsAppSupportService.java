package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.dto.MenuDto;
import com.chatcrmlite.backend.dto.SupportRequest;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.SupportFormConfig;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.services.RedisStateService;
import com.chatcrmlite.backend.services.SupportFormConfigService;
import com.chatcrmlite.backend.services.TicketService;
import com.chatcrmlite.backend.services.websocket.DistributedWebSocketPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppSupportService {

    private final WhatsAppOutboundService outboundService;
    private final RedisStateService redisStateService;
    private final DistributedWebSocketPublisher distributedWebSocketPublisher;
    private final SupportFormConfigService supportFormConfigService;
    private final TicketService ticketService;
    private final ObjectMapper objectMapper;

    public void handleSupportFlow(Contact contact, WhatsAppConfig config) {
        SupportFormConfig form = supportFormConfigService.getOrCreateConfig(config.getUser());
        if (form == null || !form.isEnabled()) {
            outboundService.sendText(contact,
                "Support is currently unavailable. Please try again later.",
                config, config.getUser());
            return;
        }

        List<String> categories = getDynamicCategories(config.getUser());
        // FIX #23: Limit categories to WhatsApp's 10-item list limit
        if (categories.size() > 10) {
            log.warn("[WhatsAppSupportService] Support categories exceed WhatsApp limit (10), truncating from {} to 10", categories.size());
            categories = categories.subList(0, 10);
        }
        
        List<MenuDto.MenuRowDto> rows = new ArrayList<>();
        for (int i = 0; i < categories.size(); i++) {
            String category = categories.get(i);
            // FIX #12: Validate category name length (WhatsApp limit: 24 chars)
            if (category == null || category.isBlank()) {
                log.warn("[WhatsAppSupportService] Skipping null/blank category at index {}", i);
                continue;
            }
            if (category.length() > 24) {
                category = category.substring(0, 24);
                log.debug("[WhatsAppSupportService] Truncated category to 24 chars");
            }
            rows.add(MenuDto.MenuRowDto.builder()
                    .id("support_cat_" + i)
                    .title(category)
                    .build());
        }

        if (rows.isEmpty()) {
            log.error("[WhatsAppSupportService] No valid categories available for support form");
            outboundService.sendText(contact,
                "Support categories are not configured. Please try again later.",
                config, config.getUser());
            return;
        }

        MenuDto menu = MenuDto.builder()
                .type("list")
                .title("Select Support Category")
                .bodyText(form.getFormDescription() != null && !form.getFormDescription().isBlank() 
                    ? form.getFormDescription() 
                    : "Please select a category for your support request:")
                .button("View Categories")
                .sections(List.of(MenuDto.MenuSectionDto.builder().rows(rows).build()))
                .build();

        try {
            outboundService.sendInteractiveMenu(contact, menu, config, config.getUser());
        } catch (Exception e) {
            log.error("[WhatsAppSupportService] Failed to send support menu: {}", e.getMessage(), e);
            outboundService.sendText(contact,
                "Sorry, we couldn't load the support menu. Please try again.",
                config, config.getUser());
        }
    }

    public void handleSupportCategorySelection(Contact contact, User owner, String selectionId, WhatsAppConfig config) {
        List<String> categories = getDynamicCategories(owner);
        String category = null;
        
        try {
            int idx = Integer.parseInt(selectionId.replace("support_cat_", ""));
            if (idx >= 0 && idx < categories.size()) {
                category = categories.get(idx);
            }
        } catch (Exception e) {
            log.warn("[Support] Invalid selection ID: {}", selectionId);
        }

        if (category == null) return;

        // Create ticket
        SupportRequest request = new SupportRequest();
        request.setName(contact.getName());
        request.setPhone(contact.getWaId());
        request.setCategory(category);
        request.setSubject("WhatsApp Support: " + category);
        request.setMessage("Auto-generated ticket from WhatsApp interaction.");

        ticketService.submitSupportRequest(owner, request);

        // Notify owner
        Map<String, Object> notification = new HashMap<>();
        notification.put("type", "SUPPORT_REQUEST");
        notification.put("contactName", contact.getName());
        notification.put("contactWaId", contact.getWaId());
        notification.put("category", category);
        distributedWebSocketPublisher.publishMessage(owner.getId(), notification);

        String successMessage = "âœ… *Support Request Submitted!*\n\n"
                + "Thank you. Your request for *" + category + "* has been received.\n"
                + "Our team will get back to you shortly.";
        
        outboundService.sendText(contact, successMessage, config, owner);
    }

    private List<String> getDynamicCategories(User owner) {
        try {
            SupportFormConfig config = supportFormConfigService.getOrCreateConfig(owner);
            String categoriesStr = config.getCategories();
            if (categoriesStr == null || categoriesStr.isBlank()) {
                return Arrays.asList("General", "Technical", "Billing", "Other");
            }
            // FIX #12: Validate and truncate category names to 24 chars
            return Arrays.stream(categoriesStr.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .map(cat -> cat.length() > 24 ? cat.substring(0, 24) : cat)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("[WhatsAppSupportService] Error loading categories: {}", e.getMessage());
            return Arrays.asList("General", "Technical", "Billing", "Other");
        }
    }
}
