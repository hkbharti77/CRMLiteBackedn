package com.chatcrmlite.backend.services.flow;

import com.chatcrmlite.backend.dto.MenuDto;
import com.chatcrmlite.backend.dto.flow.StateDef;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.repositories.BusinessServiceRepository;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import com.chatcrmlite.backend.services.SupportFormConfigService;
import com.chatcrmlite.backend.services.whatsapp.WhatsAppOutboundService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StateResolverOutboundTest {

    @Mock
    private WhatsAppOutboundService outboundService;

    @Mock
    private WhatsAppConfigRepository configRepository;

    @Mock
    private BusinessServiceRepository businessServiceRepository;

    @Mock
    private SupportFormConfigService supportFormConfigService;

    private StateResolver stateResolver;
    private User owner;
    private Contact contact;
    private WhatsAppConfig config;

    @BeforeEach
    void setUp() {
        stateResolver = new StateResolver(outboundService, configRepository, businessServiceRepository, supportFormConfigService);

        Tenant tenant = Tenant.builder()
                .id(UUID.randomUUID())
                .businessName("Test Business")
                .build();
        owner = User.builder()
                .id(UUID.randomUUID())
                .email("owner@example.com")
                .tenant(tenant)
                .build();
        contact = Contact.builder()
                .id(UUID.randomUUID())
                .waId("919900000000")
                .name("Customer")
                .owner(owner)
                .build();
        config = WhatsAppConfig.builder()
                .user(owner)
                .accessToken("token")
                .phoneNumberId("phone-id")
                .build();

        when(configRepository.findByUserId(owner.getId())).thenReturn(Optional.of(config));
    }

    @Test
    void plainFlowPromptIsSentThroughOutboundPersistenceService() {
        StateDef stateDef = StateDef.builder()
                .type(StateDef.StateType.MESSAGE)
                .text("What is your email?")
                .build();

        stateResolver.sendStateMessage(stateDef, contact, owner, 0);

        verify(outboundService).sendText(contact, "What is your email?", config, owner);
    }

    @Test
    void optionFlowPromptIsSentThroughOutboundPersistenceServiceAsInteractiveMenu() {
        StateDef stateDef = StateDef.builder()
                .type(StateDef.StateType.MESSAGE)
                .text("Choose urgency")
                .options(List.of("Today", "This week"))
                .build();

        stateResolver.sendStateMessage(stateDef, contact, owner, 0);

        ArgumentCaptor<MenuDto> menuCaptor = ArgumentCaptor.forClass(MenuDto.class);
        verify(outboundService).sendInteractiveMenu(eq(contact), menuCaptor.capture(), eq(config), eq(owner));

        MenuDto menu = menuCaptor.getValue();
        assertEquals("button", menu.getType());
        assertEquals("Choose urgency", menu.getBodyText());
        assertEquals("Today", menu.getSections().get(0).getRows().get(0).getTitle());
        assertEquals("This week", menu.getSections().get(0).getRows().get(1).getTitle());
    }
}
