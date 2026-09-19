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
        sendInteractiveAiResponse(contact, aiResponse, null, null, null, null, config, owner);
    }

    public void sendInteractiveAiResponse(Contact contact, String response, String imgUrl, WhatsAppConfig config, User owner) {
        sendInteractiveAiResponse(contact, response, imgUrl, null, null, null, config, owner);
    }

    public void sendInteractiveAiResponse(Contact contact, String response, String imgUrl, String documentUrl, String documentFilename, WhatsAppConfig config, User owner) {
        sendInteractiveAiResponse(contact, response, imgUrl, null, documentUrl, documentFilename, config, owner);
    }

    public void sendInteractiveAiResponse(Contact contact, String response, String imgUrl, String videoUrl, String documentUrl, String documentFilename, WhatsAppConfig config, User owner) {
        String formattedResponse = outboundService.convertToWhatsAppMarkdown(response);
        // Meta limit: body text <= 1024 chars
        String body = (formattedResponse != null && formattedResponse.length() > 1024) ? formattedResponse.substring(0, 1021) + "..." : formattedResponse;

        String menuJson = config != null ? config.getAiResponseMenuJson() : null;
        MenuDto menu = null;
        if (menuJson != null && !menuJson.isBlank()) {
            menu = menuService.parseCtaMenuJson(menuJson, body);
        } else {
            // Dynamic buttons from tenant configuration
            String leadLabel = (config != null && config.getLeadButtonLabel() != null && !config.getLeadButtonLabel().isBlank())
                    ? config.getLeadButtonLabel() : "Enquire Now";
            String appointmentLabel = (config != null && config.getAppointmentButtonLabel() != null && !config.getAppointmentButtonLabel().isBlank())
                    ? config.getAppointmentButtonLabel() : "Book Appointment";
            String bookingLabel = (config != null && config.getBookingButtonLabel() != null && !config.getBookingButtonLabel().isBlank())
                    ? config.getBookingButtonLabel() : "Our Services";

            List<MenuDto.MenuRowDto> rows = new ArrayList<>();
            rows.add(MenuDto.MenuRowDto.builder()
                    .id("trigger_flow_lead")
                    .title(leadLabel.length() > 20 ? leadLabel.substring(0, 20) : leadLabel)
                    .build());

            if (appointmentLabel != null && !appointmentLabel.isBlank() && rows.size() < 3) {
                rows.add(MenuDto.MenuRowDto.builder()
                        .id("trigger_flow_appointment")
                        .title(appointmentLabel.length() > 20 ? appointmentLabel.substring(0, 20) : appointmentLabel)
                        .build());
            }

            if (bookingLabel != null && !bookingLabel.isBlank() && rows.size() < 3) {
                rows.add(MenuDto.MenuRowDto.builder()
                        .id("trigger_flow_booking")
                        .title(bookingLabel.length() > 20 ? bookingLabel.substring(0, 20) : bookingLabel)
                        .build());
            }

            menu = MenuDto.builder()
                    .type("button")
                    .bodyText(body)
                    .sections(List.of(MenuDto.MenuSectionDto.builder()
                            .title("Options")
                            .rows(rows)
                            .build()))
                    .build();
        }

        if (menu == null || menu.getSections().isEmpty() || menu.getSections().get(0).getRows().isEmpty()) {
            // If the user configured the menu but removed all buttons or disabled it, send plain media or text
            if (documentUrl != null && !documentUrl.isBlank()) {
                outboundService.sendImage(contact, documentUrl, body, config, owner);
            } else if (imgUrl != null && !imgUrl.isBlank()) {
                outboundService.sendImage(contact, imgUrl, body, config, owner);
            } else {
                outboundService.sendText(contact, body, config, owner);
            }
            return;
        }

        menu.setBodyText(body);
        if (documentUrl != null && !documentUrl.isBlank()) {
            menu.setHeaderDocumentUrl(documentUrl);
            menu.setHeaderDocumentFilename(documentFilename);
        } else if (videoUrl != null && !videoUrl.isBlank()) {
            menu.setHeaderVideoUrl(videoUrl);
        } else if (imgUrl != null && !imgUrl.isBlank()) {
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
