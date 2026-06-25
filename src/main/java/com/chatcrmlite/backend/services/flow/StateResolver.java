package com.chatcrmlite.backend.services.flow;

import com.chatcrmlite.backend.dto.MenuDto;
import com.chatcrmlite.backend.dto.flow.StateDef;
import com.chatcrmlite.backend.models.BusinessService;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.repositories.BusinessServiceRepository;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import com.chatcrmlite.backend.services.SupportFormConfigService;
import com.chatcrmlite.backend.dto.SupportFormConfigDTO;
import com.chatcrmlite.backend.services.whatsapp.WhatsAppOutboundService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class StateResolver {

    private final WhatsAppOutboundService outboundService;
    private final WhatsAppConfigRepository configRepository;
    private final BusinessServiceRepository businessServiceRepository;
    private final SupportFormConfigService supportFormConfigService;

    public void sendStateMessage(StateDef stateDef, Contact contact, User owner, int pageIndex) {
        // FIX #22: Validate pageIndex
        if (pageIndex < 0) {
            log.warn("[StateResolver] Invalid pageIndex={}, resetting to 0", pageIndex);
            pageIndex = 0;
        }
        
        if (stateDef.getType() != StateDef.StateType.MESSAGE) {
            return;
        }

        WhatsAppConfig config = configRepository.findByUserId(owner.getId())
                .orElseThrow(() -> new RuntimeException("WhatsApp config not found for user: " + owner.getId()));

        // FIX #5: Validate WhatsApp config has required credentials
        if (config.getAccessToken() == null || config.getAccessToken().isBlank()) {
            log.error("[StateResolver] WhatsApp access token is missing for user={}", owner.getId());
            throw new IllegalStateException("WhatsApp access token not configured");
        }
        if (config.getPhoneNumberId() == null || config.getPhoneNumberId().isBlank()) {
            log.error("[StateResolver] WhatsApp phone number ID is missing for user={}", owner.getId());
            throw new IllegalStateException("WhatsApp phone number ID not configured");
        }

        if (stateDef.isDynamicOptions()) {
            if ("category".equals(stateDef.getSaveInputAs())) {
                sendDynamicCategoriesList(stateDef, contact, owner, config);
            } else {
                sendDynamicList(stateDef, contact, owner, config, pageIndex);
            }
            return;
        }

        if (stateDef.getOptions() != null && !stateDef.getOptions().isEmpty()) {
            if (stateDef.getOptions().size() > 3) {
                sendListMessage(stateDef, contact, owner, config);
            } else {
                sendButtonMessage(stateDef, contact, owner, config);
            }
        } else {
            // Plain text
            outboundService.sendText(contact, stateDef.getText(), config, owner);
        }
    }

    private void sendDynamicCategoriesList(StateDef stateDef, Contact contact, User owner, WhatsAppConfig config) {
        SupportFormConfigDTO supportConfig = supportFormConfigService.getPublicConfig(owner.getId(), owner);
        List<String> categories = supportConfig.getCategories();

        if (categories == null || categories.isEmpty()) {
            // Fallback
            sendFallbackText(stateDef, contact, owner, config);
            return;
        }

        List<MenuDto.MenuRowDto> rows = new ArrayList<>();
        for (int i = 0; i < Math.min(categories.size(), 10); i++) {
            String title = categories.get(i);
            if (title == null || title.isBlank()) continue;
            if (title.length() > 24) title = title.substring(0, 24);
            rows.add(MenuDto.MenuRowDto.builder()
                    .id(title) // Use the title itself as the ID to save it directly
                    .title(title)
                    .build());
        }

        MenuDto menu = MenuDto.builder()
                .type(rows.size() > 3 ? "list" : "button")
                .bodyText(stateDef.getText())
                .button(rows.size() > 3 ? "Select Category" : null)
                .sections(List.of(MenuDto.MenuSectionDto.builder().rows(rows).build()))
                .build();

        try {
            outboundService.sendInteractiveMenu(contact, menu, config, owner);
        } catch (Exception e) {
            log.error("[StateResolver] Failed to send categories menu to contact={}: {}", contact.getWaId(), e.getMessage(), e);
            sendFallbackText(stateDef, contact, owner, config);
        }
    }

    private void sendDynamicList(StateDef stateDef, Contact contact, User owner, WhatsAppConfig config, int pageIndex) {
        Page<BusinessService> servicePage = businessServiceRepository.findByOwner(owner, PageRequest.of(pageIndex, 8));

        // Fallback to static options if dynamic is empty on page 0
        if (pageIndex == 0 && servicePage.isEmpty() && stateDef.getOptions() != null && !stateDef.getOptions().isEmpty()) {
            if (stateDef.getOptions().size() > 3) {
                sendListMessage(stateDef, contact, owner, config);
            } else {
                sendButtonMessage(stateDef, contact, owner, config);
            }
            return;
        }

        List<MenuDto.MenuRowDto> rows = new ArrayList<>();
        
        for (BusinessService srv : servicePage.getContent()) {
            String title = srv.getName();
            // FIX #13: Validate title is not null or blank
            if (title == null || title.isBlank()) {
                log.warn("[StateResolver] Service id={} has null/blank name, skipping", srv.getId());
                continue;
            }
            if (title.length() > 24) {
                title = title.substring(0, 24);
            }
            rows.add(MenuDto.MenuRowDto.builder()
                    .id("srv_" + srv.getId())
                    .title(title)
                    .build());
        }

        rows.add(MenuDto.MenuRowDto.builder()
                .id("flow_other")
                .title("\u270D\uFE0F Not in list / Other")
                .description("Tell us exactly what you need")
                .build());

        if (servicePage.hasNext()) {
            rows.add(MenuDto.MenuRowDto.builder()
                    .id("flow_page_" + (pageIndex + 1))
                    .title("Next \u27A1\uFE0F")
                    .description("View more options")
                    .build());
        } else if (pageIndex > 0) {
            rows.add(MenuDto.MenuRowDto.builder()
                    .id("flow_page_0")
                    .title("\u2B05\uFE0F Back to Start")
                    .build());
        }

        MenuDto menu = MenuDto.builder()
                .type("list")
                .bodyText(stateDef.getText())
                .button("View Options")
                .sections(List.of(MenuDto.MenuSectionDto.builder().rows(rows).build()))
                .build();

        outboundService.sendInteractiveMenu(contact, menu, config, owner);
    }

    private void sendListMessage(StateDef stateDef, Contact contact, User owner, WhatsAppConfig config) {
        List<MenuDto.MenuRowDto> rows = new ArrayList<>();
        List<String> options = stateDef.getOptions();
        for (int i = 0; i < Math.min(options.size(), 10); i++) {
            String title = options.get(i);
            // FIX #13: Validate title is not null or blank
            if (title == null || title.isBlank()) {
                log.warn("[StateResolver] Option at index {} is null/blank, skipping", i);
                continue;
            }
            if (title.length() > 24) {
                title = title.substring(0, 24);
            }
            rows.add(MenuDto.MenuRowDto.builder()
                    .id("opt_" + (i + 1))
                    .title(title)
                    .build());
        }

        MenuDto menu = MenuDto.builder()
                .type("list")
                .bodyText(stateDef.getText())
                .button("Select Option")
                .sections(List.of(MenuDto.MenuSectionDto.builder().rows(rows).build()))
                .build();

        try {
            outboundService.sendInteractiveMenu(contact, menu, config, owner);
        } catch (Exception e) {
            // FIX #16: Log exception before fallback
            log.error("[StateResolver] Failed to send list menu to contact={}: {}", contact.getWaId(), e.getMessage(), e);
            sendFallbackText(stateDef, contact, owner, config);
        }
    }

    private void sendButtonMessage(StateDef stateDef, Contact contact, User owner, WhatsAppConfig config) {
        List<MenuDto.MenuRowDto> rows = new ArrayList<>();
        List<String> options = stateDef.getOptions();
        for (int i = 0; i < Math.min(options.size(), 3); i++) {
            String title = options.get(i);
            // FIX #13: Validate title is not null or blank
            if (title == null || title.isBlank()) {
                log.warn("[StateResolver] Button option at index {} is null/blank, skipping", i);
                continue;
            }
            rows.add(MenuDto.MenuRowDto.builder()
                    .id("opt_" + (i + 1))
                    .title(title)
                    .build());
        }

        MenuDto menu = MenuDto.builder()
                .type("button")
                .bodyText(stateDef.getText())
                .sections(List.of(MenuDto.MenuSectionDto.builder().rows(rows).build()))
                .build();

        try {
            outboundService.sendInteractiveMenu(contact, menu, config, owner);
        } catch (Exception e) {
            // FIX #16: Log exception before fallback
            log.error("[StateResolver] Failed to send button menu to contact={}: {}", contact.getWaId(), e.getMessage(), e);
            sendFallbackText(stateDef, contact, owner, config);
        }
    }

    private void sendFallbackText(StateDef stateDef, Contact contact, User owner, WhatsAppConfig config) {
        StringBuilder sb = new StringBuilder(stateDef.getText()).append("\n\n");
        List<String> options = stateDef.getOptions();
        if (options != null) {
            for (int i = 0; i < options.size(); i++) {
                sb.append(i + 1).append(". ").append(options.get(i)).append("\n");
            }
        }
        outboundService.sendText(contact, sb.toString(), config, owner);
    }
}
