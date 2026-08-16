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

    @Mock
    private WhatsAppMenuService whatsappMenuService;

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
                outboundService,
                whatsappMenuService
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
        when(contactRepository.findByWaIdAndTenant_Id("919900000000", owner.getId())).thenReturn(Optional.of(contact));
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

    @Test
    void testCancelFlowUsesConfiguredCtaMenu() {
        String cancelJson = "{\"enabled\":true,\"message\":\"🛑 Terminated.\",\"buttons\":[{\"id\":\"btn_1\",\"label\":\"Enquire\",\"linkType\":\"lead\"}]}";
        WhatsAppConfig config = WhatsAppConfig.builder()
                .user(owner)
                .flowCancelMenuJson(cancelJson)
                .accessToken("token")
                .phoneNumberId("phone-id")
                .build();
        when(whatsappConfigRepository.findByTenantId(owner.getId())).thenReturn(Optional.of(config));
        when(contactRepository.findByWaIdAndTenant_Id("919900000000", owner.getId())).thenReturn(Optional.of(contact));

        com.chatcrmlite.backend.dto.MenuDto expectedMenu = com.chatcrmlite.backend.dto.MenuDto.builder()
                .type("button")
                .bodyText("🛑 Terminated.")
                .sections(java.util.List.of(com.chatcrmlite.backend.dto.MenuDto.MenuSectionDto.builder()
                        .rows(java.util.List.of(com.chatcrmlite.backend.dto.MenuDto.MenuRowDto.builder().id("trigger_flow_lead").title("Enquire").build()))
                        .build()))
                .build();
        when(whatsappMenuService.parseCtaMenuJson(cancelJson, "🛑 Your form has been cancelled.\n\nHow else may we help you today?"))
                .thenReturn(expectedMenu);

        ProcessingContext context = ProcessingContext.builder()
                .messageId("wamid.cancel")
                .waId("919900000000")
                .tenantId(owner.getId())
                .payload("""
                        {
                          "entry": [
                            {
                              "changes": [
                                {
                                  "value": {
                                    "messages": [
                                      {
                                        "from": "919900000000",
                                        "id": "wamid.cancel",
                                        "type": "text",
                                        "text": { "body": "cancel" }
                                      }
                                    ]
                                  }
                                }
                              ]
                            }
                          ]
                        }
                        """)
                .build();
        context.getMetadata().put("text", "cancel");
        context.getMetadata().put("type", "text");

        flowHandler.executeFlowLogic(context);

        assertEquals("NONE", context.getMetadata().get("responseType"));
        org.mockito.Mockito.verify(outboundService).sendInteractiveMenu(contact, expectedMenu, null, config, owner);
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
