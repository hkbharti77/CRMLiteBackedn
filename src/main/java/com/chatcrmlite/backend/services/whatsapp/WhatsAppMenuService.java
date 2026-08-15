package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.dto.MenuDto;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.services.FlowTemplateEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.chatcrmlite.backend.repositories.BusinessServiceRepository;
import com.chatcrmlite.backend.models.BusinessService;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppMenuService {

    public static final String SOS_LABEL     = "\uD83C\uDD98 Human Support";
    public static final String ABOUT_LABEL   = "\uD83D\uDCC2 About & Contact";
    public static final String SUPPORT_LABEL = "\uD83C\uDFAB Get Support";
    private final WhatsAppOutboundService outboundService;
    private final FlowTemplateEngine templateEngine;
    private final ObjectMapper objectMapper;
    private final BusinessServiceRepository businessServiceRepository;
    
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private WhatsAppMessageService messageService;

    @org.springframework.beans.factory.annotation.Autowired
    private com.chatcrmlite.backend.repositories.ContactRepository contactRepository;

    @org.springframework.beans.factory.annotation.Autowired
    private com.chatcrmlite.backend.services.livechat.LiveSupportService liveSupportService;

    @org.springframework.beans.factory.annotation.Value("${app.public.url}")
    private String appPublicUrl;

    public void sendGreetingWithMenu(Contact contact, WhatsAppConfig config, User owner, boolean isNewLead) {
        String customWelcome = config.getWelcomeMessage();
        String customReturn  = config.getReturningMessage();
        String greeting;

        if (isNewLead && customWelcome != null && !customWelcome.isBlank()) {
            greeting = interpolateVariables(customWelcome, contact, owner);
        } else if (!isNewLead && customReturn != null && !customReturn.isBlank()) {
            greeting = interpolateVariables(customReturn, contact, owner);
        } else {
            String name = contact.getName();
            String displayName = (name == null || name.startsWith("WhatsApp User")) ? "there" : name.split(" ")[0];
            greeting = "\uD83D\uDC4B Hello" + (displayName.equals("there") ? "" : " " + displayName) + "!\n"
                    + "Welcome to *" + (owner.getBusinessName() != null ? owner.getBusinessName() : "our service") + "*.\n\n"
                    + "Please choose from our services below \uD83D\uDC47";
        }

        sendTenantMenuToContact(contact, config, greeting, owner);
    }

    public void sendTenantMenuToContact(Contact contact, WhatsAppConfig config) {
        sendTenantMenuToContact(contact, config, null);
    }

    public void sendTenantMenuToContact(Contact contact, WhatsAppConfig config, String overrideBodyText) {
        sendTenantMenuToContact(contact, config, overrideBodyText, resolveOwner(config));
    }

    private void sendTenantMenuToContact(Contact contact, WhatsAppConfig config, String overrideBodyText, User owner) {
        MenuDto menu = parseMenuJson(config != null ? config.getInteractiveMenuJson() : null, owner);

        if (overrideBodyText != null) menu.setBodyText(overrideBodyText);
        if (config != null) injectDynamicFeatures(menu, config);
        menu.setTitle(null); 

        // FIX #1: Validate menu before sending
        try {
            validateMenu(menu);
            outboundService.sendInteractiveMenu(contact, menu, config, owner);
        } catch (Exception e) {
            log.error("[WhatsAppMenuService] Menu validation failed, sending fallback: {}", e.getMessage());
            outboundService.sendText(contact,
                "How can we help you today? Please reply with your query.",
                config, owner);
        }
    }

    public MenuDto parseMenuJson(String json, User owner) {
        if (json == null || json.isBlank()) return buildDefaultMenu(owner);
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(json);
            String type = root.path("type").asText("list");
            String button = root.has("action") ? root.path("action").path("button").asText("View Options") : root.path("button").asText("View Options");
            String bodyText = root.path("bodyText").asText(null);

            List<MenuDto.MenuSectionDto> sections = new ArrayList<>();

            if ("button".equals(type)) {
                List<MenuDto.MenuRowDto> rows = new ArrayList<>();
                com.fasterxml.jackson.databind.JsonNode buttonsNode = root.has("action") && root.path("action").has("buttons") 
                    ? root.path("action").path("buttons") 
                    : (root.has("buttons") ? root.path("buttons") : (root.has("sections") ? root.path("sections").path(0).path("rows") : null));
                
                if (buttonsNode != null && buttonsNode.isArray()) {
                    for (com.fasterxml.jackson.databind.JsonNode btn : buttonsNode) {
                        String id = btn.path("id").asText();
                        String title = btn.path("title").asText();
                        if (btn.has("reply")) {
                            id = btn.path("reply").path("id").asText(id);
                            title = btn.path("reply").path("title").asText(title);
                        }
                        if (title != null && !title.isBlank()) {
                            rows.add(MenuDto.MenuRowDto.builder().id(id != null && !id.isBlank() ? id : "btn_" + rows.size()).title(title).build());
                        }
                    }
                }
                if (!rows.isEmpty()) {
                    sections.add(MenuDto.MenuSectionDto.builder().title("Menu").rows(rows).build());
                }
            } else {
                com.fasterxml.jackson.databind.JsonNode sectionsNode = root.has("action") && root.path("action").has("sections")
                    ? root.path("action").path("sections")
                    : root.path("sections");

                if (sectionsNode != null && sectionsNode.isArray()) {
                    for (com.fasterxml.jackson.databind.JsonNode sec : sectionsNode) {
                        String secTitle = sec.path("title").asText("Menu");
                        List<MenuDto.MenuRowDto> rows = new ArrayList<>();
                        com.fasterxml.jackson.databind.JsonNode rowsNode = sec.path("rows");
                        if (rowsNode != null && rowsNode.isArray()) {
                            for (com.fasterxml.jackson.databind.JsonNode r : rowsNode) {
                                String id = r.path("id").asText();
                                String title = r.path("title").asText();
                                String desc = r.path("description").asText(null);
                                if (title != null && !title.isBlank()) {
                                    rows.add(MenuDto.MenuRowDto.builder().id(id != null && !id.isBlank() ? id : "row_" + rows.size()).title(title).description(desc).build());
                                }
                            }
                        }
                        if (!rows.isEmpty()) {
                            sections.add(MenuDto.MenuSectionDto.builder().title(secTitle).rows(rows).build());
                        }
                    }
                }
            }

            if (sections.isEmpty() || sections.get(0).getRows() == null || sections.get(0).getRows().isEmpty()) {
                return buildDefaultMenu(owner);
            }

            MenuDto menu = MenuDto.builder()
                    .type(type)
                    .button(button)
                    .bodyText(bodyText)
                    .sections(sections)
                    .build();
            refreshTriggerLabels(menu, owner);
            return menu;
        } catch (Exception e) {
            log.error("[WhatsAppMenuService] Failed to parse custom menu JSON: {}", e.getMessage());
            return buildDefaultMenu(owner);
        }
    }

    public boolean handleCustomSubMenuTrigger(Contact contact, WhatsAppConfig config, String selectionId) {
        if (config.getCustomSubMenusJson() == null || config.getCustomSubMenusJson().isBlank()) return false;
        User owner = resolveOwner(config);
        try {
            JsonNode root = objectMapper.readTree(config.getCustomSubMenusJson());
            for (JsonNode sub : root) {
                if (selectionId.equals(sub.path("id").asText())) {
                    List<MenuDto.MenuRowDto> rows = new ArrayList<>();
                    JsonNode items = sub.path("items");
                    for (int i = 0; i < items.size(); i++) {
                        rows.add(MenuDto.MenuRowDto.builder()
                                .id(selectionId + "_i" + i)
                                .title(items.get(i).path("title").asText())
                                .description(items.get(i).path("desc").asText())
                                .build());
                    }
                    MenuDto menu = MenuDto.builder()
                            .type("list")
                            .title(sub.path("headerTitle").asText("Options"))
                            .bodyText(sub.path("bodyText").asText("Please select:"))
                            .button("View")
                            .sections(List.of(MenuDto.MenuSectionDto.builder()
                                    .title("Available Options")
                                    .rows(rows).build()))
                            .build();
                    outboundService.sendInteractiveMenu(contact, menu, config, owner);
                    return true;
                }
            }
        } catch (Exception e) {
            log.error("[WhatsAppMenuService] Failed to send sub-menu for selection={}: {}", selectionId, e.getMessage(), e);
            // FIX #14: Send fallback message to user
            try {
                outboundService.sendText(contact,
                    "Sorry, we couldn't load that menu. Please try again or contact support.",
                    config, owner);
            } catch (Exception fallbackEx) {
                log.error("[WhatsAppMenuService] Fallback message also failed: {}", fallbackEx.getMessage());
            }
            return true;
        }
        return false;
    }

    public boolean handleCustomMessageTrigger(Contact contact, WhatsAppConfig config, User owner, String selectionId) {
        if (config.getCustomMessagesJson() == null || config.getCustomMessagesJson().isBlank()) return false;
        try {
            JsonNode messages = objectMapper.readTree(config.getCustomMessagesJson());
            int idx = 0;
            for (JsonNode msg : messages) {
                idx++;
                String id = msg.has("id") && !msg.path("id").asText().isBlank() ? msg.path("id").asText() : "custom_msg_" + idx;
                if (selectionId.equals(id)) {
                    String text = msg.has("text") ? msg.path("text").asText() : msg.path("response").asText();
                    String imgUrl = msg.path("imageUrl").asText(null);

                    String body = (text != null && text.length() > 1024) ? text.substring(0, 1021) + "..." : text;
                    if (body != null && !body.isBlank()) {
                        if (imgUrl != null && !imgUrl.isBlank()) {
                            // Build a button menu with image
                            List<MenuDto.MenuRowDto> buttons = List.of(
                                MenuDto.MenuRowDto.builder().id("trigger_flow").title("Enquire Now").build()
                            );
                            MenuDto menu = MenuDto.builder()
                                    .type("button")
                                    .headerImageUrl(imgUrl)
                                    .bodyText(body)
                                    .sections(List.of(MenuDto.MenuSectionDto.builder().rows(buttons).build()))
                                    .build();
                            outboundService.sendInteractiveMenu(contact, menu, text, config, owner);
                        } else {
                            outboundService.sendText(contact, body, config, owner);
                        }
                    }
                    return true;
                }
            }
        } catch (Exception e) {
            log.error("[WhatsAppMenuService] Failed to send custom message for selection={}: {}", selectionId, e.getMessage(), e);
            try {
                outboundService.sendText(contact,
                    "Sorry, we couldn't process that request. Please try again.",
                    config, owner);
            } catch (Exception fallbackEx) {
                log.error("[WhatsAppMenuService] Fallback message also failed: {}", fallbackEx.getMessage());
            }
            return true;
        }
        return false;
    }

    public boolean handleInteractiveSelection(Contact contact, WhatsAppConfig config, User owner, String selectionId) {
        if (handleCustomSubMenuTrigger(contact, config, selectionId)) {
            return true;
        }
        if (handleCustomMessageTrigger(contact, config, owner, selectionId)) {
            return true;
        }
        if ("view_services".equals(selectionId)) {
            try {
                List<BusinessService> services = businessServiceRepository.findByOwner(owner);
                if (services.isEmpty()) {
                    outboundService.sendText(contact, "We are currently updating our catalog. Please check back later!", config, owner);
                } else {
                    List<MenuDto.MenuRowDto> rows = new ArrayList<>();
                    for (int i = 0; i < services.size() && i < 10; i++) {
                        BusinessService svc = services.get(i);
                        String title = svc.getName() != null ? svc.getName() : "Service " + (i+1);
                        if (title.length() > 24) title = title.substring(0, 24);
                        
                        String desc = svc.getDescription();
                        if (desc != null && desc.length() > 70) desc = desc.substring(0, 67) + "...";
                        
                        rows.add(MenuDto.MenuRowDto.builder()
                                .id("view_svc_" + svc.getId().toString())
                                .title(title)
                                .description(desc)
                                .build());
                    }
                    
                    MenuDto menu = MenuDto.builder()
                            .type(rows.size() > 3 ? "list" : "button")
                            .title(owner.getBusinessName() != null ? owner.getBusinessName() + " Catalog" : "Our Catalog")
                            .bodyText("Please choose an item from our catalog to see more details \uD83D\uDC47")
                            .button(rows.size() > 3 ? "View Catalog" : null)
                            .sections(List.of(MenuDto.MenuSectionDto.builder()
                                    .title("Products & Services")
                                    .rows(rows).build()))
                            .build();
                            
                    outboundService.sendInteractiveMenu(contact, menu, "Sent Catalog Menu", config, owner);
                }
            } catch (Exception e) {
                log.error("Failed to send custom catalog menu", e);
            }
            return true;
        }

        if (selectionId.startsWith("view_svc_")) {
            try {
                String svcIdStr = selectionId.replace("view_svc_", "");
                UUID svcId = UUID.fromString(svcIdStr);
                businessServiceRepository.findByIdAndOwner(svcId, owner).ifPresentOrElse(svc -> {
                    String caption = "*" + svc.getName() + "*\n\n" + (svc.getDescription() != null ? svc.getDescription() : "");
                    String imageUrl = svc.getImageUrl();
                    
                    if (imageUrl != null && !imageUrl.isBlank()) {
                        // Ensure WhatsApp can reach the image by replacing localhost with the public ngrok URL
                        if (imageUrl.contains("localhost")) {
                            int idx = imageUrl.indexOf("/public/images");
                            if (idx != -1) {
                                imageUrl = appPublicUrl + imageUrl.substring(idx);
                            }
                        }
                        messageService.sendInteractiveAiResponse(contact, caption, imageUrl, config, owner);
                    } else {
                        messageService.sendInteractiveAiResponse(contact, caption, null, config, owner);
                    }
                }, () -> {
                    outboundService.sendText(contact, "Sorry, this item is no longer available.", config, owner);
                });
            } catch (Exception e) {
                log.error("Failed to send service details", e);
            }
            return true;
        }
        if ("get_support".equals(selectionId)) {
            try {
                liveSupportService.requestHumanSupport(contact, java.util.UUID.randomUUID().toString());
            } catch (Exception e) {
                log.error("Failed to request human support for contact {}", contact.getId(), e);
            }
            return true;
        }
        if ("get_about".equals(selectionId)) {
            try {
                String about = owner.getAboutUs();
                if (about == null || about.isBlank()) about = "Welcome to " + (owner.getBusinessName() != null ? owner.getBusinessName() : "our business") + "!";
                String phone = owner.getPhone() != null ? "\nPhone: " + owner.getPhone() : "";
                String email = owner.getEmail() != null ? "\nEmail: " + owner.getEmail() : "";
                String address = owner.getAddress() != null ? "\nAddress: " + owner.getAddress() : "";
                outboundService.sendText(contact, about + "\n" + phone + email + address, config, owner);
            } catch (Exception e) {
                log.error("Failed to send about message", e);
            }
            return true;
        }

        return false;
    }

    private void injectDynamicFeatures(MenuDto menu, WhatsAppConfig config) {
        if (menu == null || menu.getSections() == null || menu.getSections().isEmpty()) return;
        
        boolean isSos = Boolean.TRUE.equals(config.getShowSosButton());
        boolean isSupportForm = Boolean.TRUE.equals(config.getShowSupportFormButton());
        boolean isAbout = Boolean.TRUE.equals(config.getShowAboutContact());

        if ("button".equals(menu.getType())) {
            List<MenuDto.MenuRowDto> rows = menu.getSections().get(0).getRows();
            if (rows == null) {
                rows = new ArrayList<>();
                menu.getSections().get(0).setRows(rows);
            }
            List<MenuDto.MenuRowDto> modifiableRows = new ArrayList<>(rows);
            
            if (modifiableRows.size() < 3 && isSupportForm && modifiableRows.stream().noneMatch(r -> "trigger_flow_support".equals(r.getId()))) {
                modifiableRows.add(MenuDto.MenuRowDto.builder().id("trigger_flow_support").title(SUPPORT_LABEL).build());
            }
            if (modifiableRows.size() < 3 && isAbout && modifiableRows.stream().noneMatch(r -> "get_about".equals(r.getId()))) {
                modifiableRows.add(MenuDto.MenuRowDto.builder().id("get_about").title(ABOUT_LABEL).build());
            }
            if (modifiableRows.size() < 3 && isSos && modifiableRows.stream().noneMatch(r -> "get_support".equals(r.getId()))) {
                modifiableRows.add(MenuDto.MenuRowDto.builder().id("get_support").title(SOS_LABEL).build());
            }
            
            menu.getSections().get(0).setRows(modifiableRows);
        } else if ("list".equals(menu.getType())) {
            List<MenuDto.MenuRowDto> dynamicRows = new ArrayList<>();
            if (isSupportForm) {
                dynamicRows.add(MenuDto.MenuRowDto.builder().id("trigger_flow_support").title(SUPPORT_LABEL).description("Submit a support request").build());
            }
            if (isAbout) {
                dynamicRows.add(MenuDto.MenuRowDto.builder().id("get_about").title(ABOUT_LABEL).description("Learn more about us and contact info").build());
            }
            if (isSos) {
                dynamicRows.add(MenuDto.MenuRowDto.builder().id("get_support").title(SOS_LABEL).description("Get help from our team").build());
            }

            if (!dynamicRows.isEmpty()) {
                List<MenuDto.MenuSectionDto> modifiableSections = new ArrayList<>(menu.getSections());
                modifiableSections.add(MenuDto.MenuSectionDto.builder().title("Support & Info").rows(dynamicRows).build());
                menu.setSections(modifiableSections);
            }
        }
    }

    private void refreshTriggerLabels(MenuDto menu, User owner) {
        if (menu == null || owner == null) {
            log.warn("[WhatsAppMenuService] Cannot refresh trigger labels: menu or owner is null");
            return;
        }
        
        List<MenuDto.MenuRowDto> flowRows = new ArrayList<>();
        boolean hasSpecificToggles = false;

        boolean hasAppointment = Boolean.TRUE.equals(owner.getForceShowAppointment());
        boolean hasBooking = Boolean.TRUE.equals(owner.getForceShowBooking());
        boolean hasLead = Boolean.TRUE.equals(owner.getForceShowLeads());

        FlowTemplateEngine.FlowBlueprint blueprint = templateEngine.getBlueprint(owner.getBusinessSubType());
        if (blueprint != null) {
            com.chatcrmlite.backend.models.ConversationState.FlowType primaryFlow = blueprint.getFlowType();
            if (primaryFlow == com.chatcrmlite.backend.models.ConversationState.FlowType.APPOINTMENT) hasAppointment = true;
            if (primaryFlow == com.chatcrmlite.backend.models.ConversationState.FlowType.BOOKING) hasBooking = true;
            if (primaryFlow == com.chatcrmlite.backend.models.ConversationState.FlowType.LEAD_CAPTURE) hasLead = true;
        }

        if (hasLead) {
            flowRows.add(MenuDto.MenuRowDto.builder()
                .id("trigger_flow_lead")
                .title(templateEngine.getTriggerButtonLabel(owner, "lead")).build());
            hasSpecificToggles = true;
        }
        if (hasAppointment) {
            flowRows.add(MenuDto.MenuRowDto.builder()
                .id("trigger_flow_appointment")
                .title(templateEngine.getTriggerButtonLabel(owner, "appointment")).build());
            hasSpecificToggles = true;
        }
        if (hasBooking) {
            flowRows.add(MenuDto.MenuRowDto.builder()
                .id("trigger_flow_booking")
                .title(templateEngine.getTriggerButtonLabel(owner, "booking")).build());
            hasSpecificToggles = true;
        }

        if (!hasSpecificToggles) {
            flowRows.add(MenuDto.MenuRowDto.builder()
                .id("trigger_flow")
                .title(templateEngine.getTriggerButtonLabel(owner)).build());
        }

        int totalRows = 0;
        if (menu.getSections() != null) {
            for (MenuDto.MenuSectionDto s : menu.getSections()) {
                if (s != null && s.getRows() != null) {
                    List<MenuDto.MenuRowDto> newRows = new ArrayList<>();
                    boolean flowRowsAdded = false;
                    for (MenuDto.MenuRowDto r : s.getRows()) {
                        if (r != null && (r.getId().equals("trigger_flow") || r.getId().startsWith("trigger_flow_"))) {
                            if (!flowRowsAdded) {
                                newRows.addAll(flowRows);
                                flowRowsAdded = true;
                            }
                        } else if (r != null) {
                            newRows.add(r);
                        }
                    }
                    s.setRows(newRows);
                    totalRows += newRows.size();
                }
            }
        }
        
        if ("button".equals(menu.getType()) && totalRows > 3) {
            menu.setType("list");
            menu.setButton("View Options");
        }
    }

    private MenuDto buildDefaultMenu(User owner) {
        List<MenuDto.MenuRowDto> rows = new ArrayList<>();
        boolean hasSpecificToggles = false;

        boolean hasAppointment = Boolean.TRUE.equals(owner.getForceShowAppointment());
        boolean hasBooking = Boolean.TRUE.equals(owner.getForceShowBooking());
        boolean hasLead = Boolean.TRUE.equals(owner.getForceShowLeads());

        FlowTemplateEngine.FlowBlueprint blueprint = templateEngine.getBlueprint(owner.getBusinessSubType());
        if (blueprint != null) {
            com.chatcrmlite.backend.models.ConversationState.FlowType primaryFlow = blueprint.getFlowType();
            if (primaryFlow == com.chatcrmlite.backend.models.ConversationState.FlowType.APPOINTMENT) hasAppointment = true;
            if (primaryFlow == com.chatcrmlite.backend.models.ConversationState.FlowType.BOOKING) hasBooking = true;
            if (primaryFlow == com.chatcrmlite.backend.models.ConversationState.FlowType.LEAD_CAPTURE) hasLead = true;
        }

        if (hasLead) {
            rows.add(MenuDto.MenuRowDto.builder()
                .id("trigger_flow_lead")
                .title(templateEngine.getTriggerButtonLabel(owner, "lead")).build());
            hasSpecificToggles = true;
        }
        if (hasAppointment) {
            rows.add(MenuDto.MenuRowDto.builder()
                .id("trigger_flow_appointment")
                .title(templateEngine.getTriggerButtonLabel(owner, "appointment")).build());
            hasSpecificToggles = true;
        }
        if (hasBooking) {
            rows.add(MenuDto.MenuRowDto.builder()
                .id("trigger_flow_booking")
                .title(templateEngine.getTriggerButtonLabel(owner, "booking")).build());
            hasSpecificToggles = true;
        }

        if (!hasSpecificToggles) {
            rows.add(MenuDto.MenuRowDto.builder()
                .id("trigger_flow")
                .title(templateEngine.getTriggerButtonLabel(owner)).build());
        }

        String type = "button";
        String buttonText = null;
        if (rows.size() > 2) {
            type = "list";
            buttonText = "View Options";
        }

        return MenuDto.builder()
                .type(type)
                .button(buttonText)
                .bodyText("How can we help you?")
                .sections(List.of(MenuDto.MenuSectionDto.builder().title("Our Services").rows(rows).build()))
                .build();
    }

    private String interpolateVariables(String text, Contact contact, User owner) {
        String name = contact.getName();
        String displayName = (name == null || name.startsWith("WhatsApp User")) ? "there" : name.split(" ")[0];
        String bizName = (owner.getBusinessName() != null) ? owner.getBusinessName() : "our business";
        return text.replace("{{name}}", displayName).replace("{{business}}", bizName);
    }

    private User resolveOwner(WhatsAppConfig config) {
        User owner = config.getUser();
        if (owner == null) {
            throw new IllegalStateException("WhatsApp config has no owner user");
        }
        return owner;
    }

    public void validateMenu(MenuDto menu) {
        // FIX #19: Enhanced validation now actually used
        if (menu == null) {
            throw new IllegalArgumentException("Menu cannot be null");
        }
        if (menu.getType() == null || menu.getType().isBlank()) {
            throw new IllegalArgumentException("Menu type must be 'button' or 'list'");
        }
        if (!"button".equals(menu.getType()) && !"list".equals(menu.getType())) {
            throw new IllegalArgumentException("Menu type must be 'button' or 'list', got: " + menu.getType());
        }
        if (menu.getSections() == null || menu.getSections().isEmpty()) {
            throw new IllegalArgumentException("Menu must have at least one section");
        }
        
        int totalRows = 0;
        for (MenuDto.MenuSectionDto section : menu.getSections()) {
            if (section != null && section.getRows() != null) {
                for (MenuDto.MenuRowDto row : section.getRows()) {
                    if (row != null) {
                        if (row.getId() == null || row.getId().isBlank()) {
                            throw new IllegalArgumentException("Menu row must have an ID");
                        }
                        if (row.getTitle() == null || row.getTitle().isBlank()) {
                            throw new IllegalArgumentException("Menu row must have a title");
                        }
                        if (row.getTitle().length() > 24) {
                            throw new IllegalArgumentException("Menu row title cannot exceed 24 characters: " + row.getTitle());
                        }
                        totalRows++;
                    }
                }
            }
        }
        
        if ("button".equals(menu.getType()) && totalRows > 3) {
            throw new IllegalArgumentException("WhatsApp button menus are limited to 3 buttons, got: " + totalRows);
        }
        if ("list".equals(menu.getType()) && totalRows > 10) {
            throw new IllegalArgumentException("WhatsApp list menus are limited to 10 items, got: " + totalRows);
        }
        if (totalRows == 0) {
            throw new IllegalArgumentException("Menu must have at least one row");
        }
    }
}
