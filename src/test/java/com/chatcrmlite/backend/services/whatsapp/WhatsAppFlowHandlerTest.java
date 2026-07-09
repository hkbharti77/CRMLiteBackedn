package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import com.chatcrmlite.backend.services.flow.FlowStateMachine;
import com.chatcrmlite.backend.services.workflow.ProcessingContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WhatsAppFlowHandlerTest {

    @Mock
    private WhatsAppConfigRepository whatsappConfigRepository;

    @Mock
    private ContactRepository contactRepository;

    @Mock
    private FlowStateMachine flowStateMachine;

    @Mock
    private WhatsAppOutboundService outboundService;

    private WhatsAppFlowHandler flowHandler;
    private User owner;
    private Contact contact;

    @BeforeEach
    void setUp() {
        flowHandler = new WhatsAppFlowHandler(
                whatsappConfigRepository,
                contactRepository,
                flowStateMachine,
                new ObjectMapper(),
                outboundService
        );

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
                .owner(owner)
                .build();
    }

    @Test
    void consumedFlowKeepsDeliveryResponseTypeAsFlowConsumed() {
        WhatsAppConfig config = WhatsAppConfig.builder()
                .user(owner)
                .accessToken("token")
                .phoneNumberId("phone-id")
                .build();
        when(whatsappConfigRepository.findByTenantId(owner.getId())).thenReturn(Optional.of(config));
        when(contactRepository.findByWaIdAndOwner("919900000000", owner)).thenReturn(Optional.of(contact));
        when(flowStateMachine.processFlow(contact, owner, "Need a website", null, false)).thenReturn(true);

        ProcessingContext context = ProcessingContext.builder()
                .messageId("wamid.incoming")
                .waId("919900000000")
                .tenantId(owner.getId())
                .payload(textPayload())
                .build();
        context.getMetadata().put("text", "Need a website");
        context.getMetadata().put("type", "text");

        flowHandler.executeFlowLogic(context);

        assertEquals("FLOW_CONSUMED", context.getMetadata().get("responseType"));
    }

    private String textPayload() {
        return """
                {
                  "entry": [
                    {
                      "changes": [
                        {
                          "value": {
                            "messages": [
                              {
                                "from": "919900000000",
                                "id": "wamid.incoming",
                                "type": "text",
                                "text": { "body": "Need a website" }
                              }
                            ]
                          }
                        }
                      ]
                    }
                  ]
                }
                """;
    }
}
