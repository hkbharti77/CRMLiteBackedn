package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import com.chatcrmlite.backend.services.workflow.ProcessingContext;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WhatsAppDeliveryHandler {
    private static final Logger log = LoggerFactory.getLogger(WhatsAppDeliveryHandler.class);

    private final WhatsAppConfigRepository whatsappConfigRepository;
    private final ContactRepository contactRepository;
    private final WhatsAppMessageService messageService;
    private final WhatsAppMenuService menuService;
    @Autowired(required = false) private UserRepository userRepository;

    public void deliverResponse(ProcessingContext context) throws Exception {
        try {
            WhatsAppConfig config = whatsappConfigRepository.findByTenantId(context.getTenantId())
                    .orElseThrow(() -> new IllegalStateException("WhatsApp config not found for tenant: " + context.getTenantId()));

            User owner = config.getUser();
            if (owner == null && userRepository != null && config.getTenant() != null) {
                owner = userRepository.findByTenantIdAndRole(context.getTenantId(), User.Role.OWNER)
                        .stream().findFirst()
                        .orElseGet(() -> userRepository.findAllByTenant(config.getTenant()).stream().findFirst().orElse(null));
            }

            Contact contact = contactRepository.findByWaIdAndTenant_Id(context.getWaId(), context.getTenantId())
                    .orElseThrow(() -> new IllegalStateException("Contact not found for tenant: " + context.getTenantId()));

            String responseType = (String) context.getMetadata().getOrDefault("responseType", "NONE");
            String pendingResponse = (String) context.getMetadata().get("pendingResponse");

            log.info("[WhatsApp-Outbound] Dispatching response responseType={} correlationId={} messageId={}",
                    responseType, context.getMessageId(), context.getMessageId());

            switch (responseType) {
                case "AI":
                case "PLAIN":
                    if (pendingResponse != null) {
                        messageService.sendInteractiveAiResponse(contact, pendingResponse, config, owner);
                    }
                    break;
                case "GREETING":
                    menuService.sendGreetingWithMenu(contact, config, owner,
                            (Boolean) context.getMetadata().getOrDefault("isNewContact", false));
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
                case "MENU_OVERRIDE":
                    menuService.sendTenantMenuToContact(contact, config, pendingResponse);
                    break;
                case "FLOW_CONSUMED":
                case "NONE":
                    break;
            }
            log.info("[WhatsApp-Outbound] Successfully dispatched response responseType={} correlationId={}",
                    responseType, context.getMessageId());
        } catch (Exception e) {
            log.error("[WhatsApp-Outbound] FAILED delivery for messageId={} error={}", context.getMessageId(), e.getMessage(), e);
            throw e;
        }
    }
}
