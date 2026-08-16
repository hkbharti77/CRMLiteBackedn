package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.clients.WhatsAppClient;
import com.chatcrmlite.backend.dto.MenuDto;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Message;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.repositories.MessageRepository;
import com.chatcrmlite.backend.services.websocket.DistributedWebSocketPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WhatsAppOutboundServiceTest {

    @Mock
    private WhatsAppClient whatsappClient;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private DistributedWebSocketPublisher distributedWebSocketPublisher;

    private WhatsAppOutboundService outboundService;
    private User owner;
    private Contact contact;
    private WhatsAppConfig config;

    @BeforeEach
    void setUp() {
        outboundService = new WhatsAppOutboundService(whatsappClient, messageRepository, distributedWebSocketPublisher);

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
        contact.setTenant(tenant);
        config = WhatsAppConfig.builder()
                .user(owner)
                .accessToken("token")
                .phoneNumberId("phone-id")
                .build();
    }

    @Test
    void sendTextPersistsOutgoingMessageAndPublishesWebSocketEvent() {
        stubSaveAssignsId();
        when(whatsappClient.sendMessage("919900000000", "Hello", "token", "phone-id"))
                .thenReturn("wamid.123");

        Message saved = outboundService.sendText(contact, "Hello", config, owner);

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).save(messageCaptor.capture());
        Message persisted = messageCaptor.getValue();

        assertSame(saved, persisted);
        assertEquals("wamid.123", persisted.getWaMessageId());
        assertEquals("Hello", persisted.getContent());
        assertEquals(Message.Direction.OUTGOING, persisted.getDirection());
        assertSame(contact, persisted.getContact());
        assertSame(owner, persisted.getOwner());
        assertNotNull(persisted.getTimestamp());
        verify(distributedWebSocketPublisher).publishMessage(eq(owner.getTenant().getId()), any());
    }

    @Test
    void sendTextUsesUniqueLocalMessageIdWhenMetaDoesNotReturnOne() {
        stubSaveAssignsId();
        when(whatsappClient.sendMessage("919900000000", "Hello", "token", "phone-id"))
                .thenReturn("unknown_id");

        outboundService.sendText(contact, "Hello", config, owner);

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).save(messageCaptor.capture());
        assertTrue(messageCaptor.getValue().getWaMessageId().startsWith("local:"));
    }

    @Test
    void sendTextDoesNotPersistWhenWhatsAppSendFails() {
        RuntimeException failure = new RuntimeException("Meta rejected request");
        when(whatsappClient.sendMessage("919900000000", "Hello", "token", "phone-id"))
                .thenThrow(failure);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> outboundService.sendText(contact, "Hello", config, owner));

        assertSame(failure, thrown);
        verify(messageRepository, never()).save(any());
        verify(distributedWebSocketPublisher, never()).publishMessage(any(), any());
    }

    @Test
    void sendInteractiveMenuPersistsReadableBodyAndOptionLabels() {
        stubSaveAssignsId();
        MenuDto menu = MenuDto.builder()
                .type("button")
                .bodyText("Choose a service")
                .sections(List.of(MenuDto.MenuSectionDto.builder()
                        .rows(List.of(
                                MenuDto.MenuRowDto.builder().id("a").title("Design").build(),
                                MenuDto.MenuRowDto.builder().id("b").title("Support").build()
                        ))
                        .build()))
                .build();
        when(whatsappClient.sendInteractiveMenu("919900000000", menu, "token", "phone-id"))
                .thenReturn("wamid.menu");

        outboundService.sendInteractiveMenu(contact, menu, config, owner);

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).save(messageCaptor.capture());
        assertEquals("Choose a service\n\nOptions: Design, Support", messageCaptor.getValue().getContent());
    }

    private void stubSaveAssignsId() {
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> {
            Message message = invocation.getArgument(0);
            message.setId(UUID.randomUUID());
            return message;
        });
    }
}
