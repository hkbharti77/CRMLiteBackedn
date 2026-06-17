package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.dto.MenuDto;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class WhatsAppMessageService {

    private final WhatsAppOutboundService outboundService;
    private final ContactRepository contactRepository;
    private final WhatsAppConfigRepository whatsappConfigRepository;

    // @Lazy breaks the circular dependency:
    // WhatsAppMessageService -> WhatsAppMenuService -> WhatsAppMessageService
    private final WhatsAppMenuService menuService;

    @Autowired
    public WhatsAppMessageService(
            WhatsAppOutboundService outboundService,
            ContactRepository contactRepository,
            WhatsAppConfigRepository whatsappConfigRepository,
            @Lazy WhatsAppMenuService menuService) {
        this.outboundService = outboundService;
        this.contactRepository = contactRepository;
        this.whatsappConfigRepository = whatsappConfigRepository;
        this.menuService = menuService;
    }

    public void sendInteractiveAiResponse(Contact contact, String aiResponse, WhatsAppConfig config, User owner) {
        sendInteractiveAiResponse(contact, aiResponse, null, config, owner);
    }

    public void sendInteractiveAiResponse(Contact contact, String response, String imgUrl, WhatsAppConfig config, User owner) {
        // Meta limit: body text <= 1024 chars
        String body = (response != null && response.length() > 1024) ? response.substring(0, 1021) + "..." : response;

        List<MenuDto.MenuRowDto> buttons = new ArrayList<>();
        String menuJson = config.getAiResponseMenuJson();
        
        if (menuJson != null && !menuJson.isBlank()) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(menuJson);
                com.fasterxml.jackson.databind.JsonNode rows = root.at("/sections/0/rows");
                if (rows.isArray()) {
                    for (com.fasterxml.jackson.databind.JsonNode row : rows) {
                        buttons.add(MenuDto.MenuRowDto.builder()
                                .id(row.get("id").asText())
                                .title(row.get("title").asText())
                                .build());
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to parse aiResponseMenuJson, falling back to default.", e);
            }
        } else {
            // Default backward compatibility if not configured
            buttons.add(MenuDto.MenuRowDto.builder().id("trigger_flow").title("Enquire Now").build());
        }

        if (buttons.isEmpty()) {
            // If the user configured the menu but removed all buttons, just send plain text
            outboundService.sendText(contact, body, config, owner);
            return;
        }

        MenuDto menu = MenuDto.builder()
                .type("button")
                .headerImageUrl(imgUrl)
                .bodyText(body)
                .sections(List.of(MenuDto.MenuSectionDto.builder().rows(buttons).build()))
                .build();

        try {
            outboundService.sendInteractiveMenu(contact, menu, response, config, owner);
        } catch (Exception e) {
            log.error("[RAG-Interactive] Failed to send interactive response: {}", e.getMessage());
            outboundService.sendText(contact, body, config, owner);
        }
    }

    public void sendMessage(UUID contactId, String text, User owner) {
        Contact contact = contactRepository.findById(contactId)
                .filter(c -> c.getOwner().getId().equals(owner.getId()))
                .orElseThrow(() -> new RuntimeException("Contact not found"));

        WhatsAppConfig config = whatsappConfigRepository.findByUserId(owner.getId())
                .orElseThrow(() -> new RuntimeException("WhatsApp config not found"));

        outboundService.sendText(contact, text, config, owner);
    }

    public void sendTenantMenu(UUID contactId, User owner) {
        Contact contact = contactRepository.findById(contactId)
                .filter(c -> c.getOwner().getId().equals(owner.getId()))
                .orElseThrow(() -> new RuntimeException("Contact not found"));

        WhatsAppConfig config = whatsappConfigRepository.findByUserId(owner.getId())
                .orElseThrow(() -> new RuntimeException("WhatsApp config not found"));

        menuService.sendTenantMenuToContact(contact, config);
    }

}
