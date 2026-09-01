package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.dto.MenuDto;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.repositories.BusinessServiceRepository;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.services.FlowTemplateEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WhatsAppMenuCustomLabelTest {

    @Mock
    private WhatsAppOutboundService outboundService;

    @Mock
    private FlowTemplateEngine templateEngine;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private BusinessServiceRepository businessServiceRepository;

    @Mock
    private ContactRepository contactRepository;

    @InjectMocks
    private WhatsAppMenuService menuService;

    private User testUser;
    private Tenant testTenant;

    @BeforeEach
    void setUp() {
        testTenant = new Tenant();
        testTenant.setId(UUID.randomUUID());
        testTenant.setBusinessName("Test Enterprise");
        testTenant.setBusinessSubType("dental");

        testUser = new User();
        testUser.setId(UUID.randomUUID());
        testUser.setEmail("test@crm.com");
        testUser.setTenant(testTenant);
        testUser.setForceShowLeads(true);

        lenient().when(templateEngine.getTriggerButtonLabel(any(User.class), eq("lead")))
                .thenReturn("Enquire Now");
        lenient().when(templateEngine.getTriggerButtonLabel(any(User.class), eq("appointment")))
                .thenReturn("Book Appointment");
        lenient().when(templateEngine.getTriggerButtonLabel(any(User.class), eq("booking")))
                .thenReturn("Book Service");
        lenient().when(templateEngine.getTriggerButtonLabel(any(User.class)))
                .thenReturn("Enquire Now");
    }

    @Test
    void testBuildDefaultMenu_DefaultFallbackWhenNoCustomLabel() {
        WhatsAppConfig config = new WhatsAppConfig();
        config.setUser(testUser);
        config.setTenant(testTenant);
        // leadButtonLabel is null

        MenuDto menu = menuService.buildDefaultMenu(testUser, config);

        assertNotNull(menu);
        assertNotNull(menu.getSections());
        assertFalse(menu.getSections().isEmpty());

        MenuDto.MenuRowDto leadRow = menu.getSections().get(0).getRows().stream()
                .filter(r -> "trigger_flow_lead".equals(r.getId()))
                .findFirst()
                .orElse(null);

        assertNotNull(leadRow, "trigger_flow_lead button should be present");
        assertEquals("Enquire Now", leadRow.getTitle(), "Default label should be Enquire Now");
        assertDoesNotThrow(() -> menuService.validateMenu(menu));
    }

    @Test
    void testBuildDefaultMenu_CustomLeadLabelApplied() {
        WhatsAppConfig config = new WhatsAppConfig();
        config.setUser(testUser);
        config.setTenant(testTenant);
        config.setLeadButtonLabel("Admission Enquiry");

        MenuDto menu = menuService.buildDefaultMenu(testUser, config);

        assertNotNull(menu);
        MenuDto.MenuRowDto leadRow = menu.getSections().get(0).getRows().stream()
                .filter(r -> "trigger_flow_lead".equals(r.getId()))
                .findFirst()
                .orElse(null);

        assertNotNull(leadRow);
        assertEquals("trigger_flow_lead", leadRow.getId(), "Button ID must remain trigger_flow_lead for routing");
        assertEquals("Admission Enquiry", leadRow.getTitle(), "Custom label must be applied");
        assertDoesNotThrow(() -> menuService.validateMenu(menu));
    }

    @Test
    void testBuildDefaultMenu_CustomAppointmentAndBookingLabels() {
        testUser.setForceShowAppointment(true);
        testUser.setForceShowBooking(true);

        WhatsAppConfig config = new WhatsAppConfig();
        config.setUser(testUser);
        config.setTenant(testTenant);
        config.setLeadButtonLabel("Get Quotation");
        config.setAppointmentButtonLabel("Doctor Consult");
        config.setBookingButtonLabel("Reserve Table");

        MenuDto menu = menuService.buildDefaultMenu(testUser, config);

        assertNotNull(menu);
        MenuDto.MenuRowDto leadRow = menu.getSections().get(0).getRows().stream()
                .filter(r -> "trigger_flow_lead".equals(r.getId())).findFirst().orElse(null);
        MenuDto.MenuRowDto apptRow = menu.getSections().get(0).getRows().stream()
                .filter(r -> "trigger_flow_appointment".equals(r.getId())).findFirst().orElse(null);
        MenuDto.MenuRowDto bookingRow = menu.getSections().get(0).getRows().stream()
                .filter(r -> "trigger_flow_booking".equals(r.getId())).findFirst().orElse(null);

        assertNotNull(leadRow);
        assertEquals("Get Quotation", leadRow.getTitle());

        assertNotNull(apptRow);
        assertEquals("Doctor Consult", apptRow.getTitle());

        assertNotNull(bookingRow);
        assertEquals("Reserve Table", bookingRow.getTitle());

        assertDoesNotThrow(() -> menuService.validateMenu(menu));
    }

    @Test
    void testCustomLabel_TruncatedSafelyIfExceeds24Chars() {
        WhatsAppConfig config = new WhatsAppConfig();
        config.setUser(testUser);
        config.setTenant(testTenant);
        // 35 characters long string
        config.setLeadButtonLabel("Very Long Custom Label Exceeding 24");

        MenuDto menu = menuService.buildDefaultMenu(testUser, config);

        assertNotNull(menu);
        MenuDto.MenuRowDto leadRow = menu.getSections().get(0).getRows().stream()
                .filter(r -> "trigger_flow_lead".equals(r.getId())).findFirst().orElse(null);

        assertNotNull(leadRow);
        assertTrue(leadRow.getTitle().length() <= 24, "Label must be capped to at most 24 characters");
        assertEquals("Very Long Custom Label E", leadRow.getTitle());
        // Verify WhatsApp menu validation passes without exception
        assertDoesNotThrow(() -> menuService.validateMenu(menu));
    }

    @Test
    void testParseMenuJson_PreservesCustomTitleFromInteractiveJsonWhenNoExplicitConfigOverride() {
        // User provided custom interactiveMenuJson with a custom title for trigger_flow_lead
        String customJson = """
        {
          "type": "button",
          "bodyText": "Welcome! Please choose:",
          "buttons": [
            {"id": "trigger_flow_lead", "title": "Apply For Loan"},
            {"id": "menu", "title": "Main Menu"}
          ]
        }
        """;

        WhatsAppConfig config = new WhatsAppConfig();
        config.setUser(testUser);
        config.setTenant(testTenant);
        config.setInteractiveMenuJson(customJson);
        // leadButtonLabel is null (user didn't set field override, but wrote custom json)

        MenuDto menu = menuService.parseMenuJson(customJson, testUser, config);

        assertNotNull(menu);
        MenuDto.MenuRowDto leadRow = menu.getSections().get(0).getRows().stream()
                .filter(r -> "trigger_flow_lead".equals(r.getId())).findFirst().orElse(null);

        assertNotNull(leadRow);
        assertEquals("Apply For Loan", leadRow.getTitle(), "Custom title in interactiveMenuJson should be preserved");
    }

    @Test
    void testParseMenuJson_ExplicitConfigLabelOverridesInteractiveJsonTitle() {
        // User has custom json with "Apply For Loan", but set leadButtonLabel to "Apply Now"
        String customJson = """
        {
          "type": "button",
          "bodyText": "Welcome! Please choose:",
          "buttons": [
            {"id": "trigger_flow_lead", "title": "Apply For Loan"}
          ]
        }
        """;

        WhatsAppConfig config = new WhatsAppConfig();
        config.setUser(testUser);
        config.setTenant(testTenant);
        config.setInteractiveMenuJson(customJson);
        config.setLeadButtonLabel("Apply Now");

        MenuDto menu = menuService.parseMenuJson(customJson, testUser, config);

        assertNotNull(menu);
        MenuDto.MenuRowDto leadRow = menu.getSections().get(0).getRows().stream()
                .filter(r -> "trigger_flow_lead".equals(r.getId())).findFirst().orElse(null);

        assertNotNull(leadRow);
        assertEquals("Apply Now", leadRow.getTitle(), "Explicit leadButtonLabel takes priority over interactiveMenuJson title");
    }
}
