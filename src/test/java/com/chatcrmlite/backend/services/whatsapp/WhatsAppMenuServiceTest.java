package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.dto.MenuDto;
import com.chatcrmlite.backend.repositories.BusinessServiceRepository;
import com.chatcrmlite.backend.services.FlowTemplateEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class WhatsAppMenuServiceTest {

    @Mock
    private WhatsAppOutboundService outboundService;

    @Mock
    private FlowTemplateEngine templateEngine;

    @Mock
    private BusinessServiceRepository businessServiceRepository;

    private WhatsAppMenuService menuService;

    @BeforeEach
    void setUp() {
        menuService = new WhatsAppMenuService(
                outboundService,
                templateEngine,
                new ObjectMapper(),
                businessServiceRepository
        );
    }

    @Test
    void testParseCtaMenuJsonFromFlowCTAPanel() {
        String json = """
                {
                  "enabled": true,
                  "message": "🛑 Form cancelled.",
                  "buttons": [
                    { "id": "btn_1", "label": "Enquire Now", "linkType": "lead" },
                    { "id": "btn_2", "label": "Book Appointment", "linkType": "appointment" },
                    { "id": "btn_3", "label": "Our Services", "linkType": "catalog" }
                  ]
                }
                """;

        MenuDto menu = menuService.parseCtaMenuJson(json, "Default Body");
        assertNotNull(menu);
        assertEquals("button", menu.getType());
        assertEquals("🛑 Form cancelled.", menu.getBodyText());
        assertEquals(1, menu.getSections().size());
        assertEquals(3, menu.getSections().get(0).getRows().size());

        assertEquals("trigger_flow_lead", menu.getSections().get(0).getRows().get(0).getId());
        assertEquals("Enquire Now", menu.getSections().get(0).getRows().get(0).getTitle());

        assertEquals("trigger_flow_appointment", menu.getSections().get(0).getRows().get(1).getId());
        assertEquals("Book Appointment", menu.getSections().get(0).getRows().get(1).getTitle());

        assertEquals("view_services", menu.getSections().get(0).getRows().get(2).getId());
        assertEquals("Our Services", menu.getSections().get(0).getRows().get(2).getTitle());
    }

    @Test
    void testParseCtaMenuJsonDisabledReturnsNull() {
        String json = """
                {
                  "enabled": false,
                  "message": "Cancelled.",
                  "buttons": [
                    { "id": "btn_1", "label": "Enquire Now", "linkType": "lead" }
                  ]
                }
                """;

        MenuDto menu = menuService.parseCtaMenuJson(json, "Default Body");
        assertNull(menu);
    }

    @Test
    void testParseCtaMenuJsonStandardMenuDtoFormat() {
        String json = """
                {
                  "type": "button",
                  "bodyText": "Select an action:",
                  "sections": [
                    {
                      "rows": [
                        { "id": "trigger_flow_lead", "title": "Enquire" }
                      ]
                    }
                  ]
                }
                """;

        MenuDto menu = menuService.parseCtaMenuJson(json, "Default Body");
        assertNotNull(menu);
        assertEquals("button", menu.getType());
        assertEquals("Select an action:", menu.getBodyText());
        assertEquals(1, menu.getSections().size());
        assertEquals(1, menu.getSections().get(0).getRows().size());
        assertEquals("trigger_flow_lead", menu.getSections().get(0).getRows().get(0).getId());
        assertEquals("Enquire", menu.getSections().get(0).getRows().get(0).getTitle());
    }
}
