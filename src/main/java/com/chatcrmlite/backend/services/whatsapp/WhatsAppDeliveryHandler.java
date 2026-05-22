package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import com.chatcrmlite.backend.services.workflow.ProcessingContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppDeliveryHandler {

    private final WhatsAppConfigRepository whatsappConfigRepository;
    private final ContactRepository contactRepository;
    private final WhatsAppMessageService messageService;
    private final WhatsAppMenuService menuService;

    @Transactional
    public void deliverResponse(ProcessingContext context) {
        try {
            WhatsAppConfig config = whatsappConfigRepository.findByUserId(context.getTenantId())
                    .orElseThrow(() -> new RuntimeException("Config not found"));
            User owner = config.getUser();
            Contact contact = contactRepository.findByWaIdAndOwner(context.getWaId(), owner)
                    .orElseThrow(() -> new RuntimeException("Contact not found"));

            String responseType = (String) context.getMetadata().getOrDefault("responseType", "NONE");
            String pendingResponse = (String) context.getMetadata().get("pendingResponse");

            switch (responseType) {
                case "AI":
                case "PLAIN":
                    if (pendingResponse != null) {
                        messageService.sendInteractiveAiResponse(contact, pendingResponse, config, owner);
                    }
                    break;
                case "GREETING":
                    menuService.sendGreetingWithMenu(contact, config, owner, (Boolean) context.getMetadata().getOrDefault("isNewContact", false));
                    break;
                case "INTERACTIVE_SELECTION":
                    String selectionId = (String) context.getMetadata().get("selectionId");
                    boolean handled = menuService.handleInteractiveSelection(contact, config, owner, selectionId);
                    if (!handled) {
                        menuService.sendTenantMenuToContact(contact, config);
                    }
                    break;
                case "MENU":
                    menuService.sendTenantMenuToContact(contact, config);
                    break;
                case "FLOW_CONSUMED":
                case "NONE":
                    break;
            }
            log.info("🚚 [Delivery-Stage] Dispatched response type {} for {}", responseType, context.getMessageId());
        } catch (Exception e) {
            log.error("❌ [Delivery-Stage] Failed for {}", context.getMessageId(), e);
        }
    }
}
