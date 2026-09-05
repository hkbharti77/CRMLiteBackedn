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
import org.springframework.transaction.annotation.Transactional;

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
        String formattedResponse = outboundService.convertToWhatsAppMarkdown(response);
        // Meta limit: body text <= 1024 chars
        String body = (formattedResponse != null && formattedResponse.length() > 1024) ? formattedResponse.substring(0, 1021) + "..." : formattedResponse;

        String menuJson = config != null ? config.getAiResponseMenuJson() : null;
        MenuDto menu = null;
        if (menuJson != null && !menuJson.isBlank()) {
            menu = menuService.parseCtaMenuJson(menuJson, body);
        } else {
            // Default backward compatibility if not configured
            menu = MenuDto.builder()
                    .type("button")
                    .bodyText(body)
                    .sections(List.of(MenuDto.MenuSectionDto.builder()
                            .title("Options")
                            .rows(List.of(MenuDto.MenuRowDto.builder().id("trigger_flow").title("Enquire Now").build()))
                            .build()))
                    .build();
        }

        if (menu == null || menu.getSections().isEmpty() || menu.getSections().get(0).getRows().isEmpty()) {
            // If the user configured the menu but removed all buttons or disabled it, send plain text
            outboundService.sendText(contact, body, config, owner);
            return;
        }

        menu.setBodyText(body);
        if (imgUrl != null && !imgUrl.isBlank()) {
            menu.setHeaderImageUrl(imgUrl);
        }

        try {
            outboundService.sendInteractiveMenu(contact, menu, response, config, owner);
        } catch (Exception e) {
            log.error("[RAG-Interactive] Failed to send interactive response: {}", e.getMessage());
            outboundService.sendText(contact, body, config, owner);
        }
    }

    @Transactional
    public void sendMessage(UUID contactId, String text, User currentUser) {
        Contact contact = contactRepository.findById(contactId)
                .filter(c -> c.getOwner() != null && c.getOwner().getTenant() != null && 
                             c.getOwner().getTenant().getId().equals(currentUser.getTenant().getId()))
                .orElseThrow(() -> new IllegalArgumentException("Contact not found for tenant: " + currentUser.getTenant().getId()));

        WhatsAppConfig config = whatsappConfigRepository.findByTenantId(currentUser.getTenant().getId())
                .orElseThrow(() -> new IllegalStateException("WhatsApp configuration not found for tenant: " + currentUser.getTenant().getId()));

        User owner = config.getUser() != null ? config.getUser() : currentUser;
        
        // Manual agent/admin reply: send cleanly as text
        outboundService.sendText(contact, text, config, owner);
    }

    @Transactional
    public void sendTenantMenu(UUID contactId, User currentUser) {
        Contact contact = contactRepository.findById(contactId)
                .filter(c -> c.getOwner() != null && c.getOwner().getTenant() != null && 
                             c.getOwner().getTenant().getId().equals(currentUser.getTenant().getId()))
                .orElseThrow(() -> new IllegalArgumentException("Contact not found for tenant: " + currentUser.getTenant().getId()));

        WhatsAppConfig config = whatsappConfigRepository.findByTenantId(currentUser.getTenant().getId())
                .orElseThrow(() -> new IllegalStateException("WhatsApp configuration not found for tenant: " + currentUser.getTenant().getId()));

        menuService.sendTenantMenuToContact(contact, config);
    }

}
