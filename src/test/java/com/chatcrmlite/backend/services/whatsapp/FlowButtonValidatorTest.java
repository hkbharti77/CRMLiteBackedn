package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.services.whatsapp.validation.FlowButtonValidator;
import com.chatcrmlite.backend.dto.WhatsAppTemplateDto.TemplateButtonDto;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.flows.FlowLifecycleStatus;
import com.chatcrmlite.backend.models.flows.WhatsAppFlow;
import com.chatcrmlite.backend.repositories.flows.WhatsAppFlowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FlowButtonValidatorTest {

    @Mock
    private WhatsAppFlowRepository flowRepository;

    @InjectMocks
    private FlowButtonValidator validator;

    private User user;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        Tenant tenant = new Tenant();
        tenant.setId(tenantId);

        user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("admin@gyanvaniai.online");
        user.setTenant(tenant);
    }

    @Test
    @DisplayName("Allows valid published flow button owned by tenant")
    void testValidPublishedFlowButton() {
        String flowId = "1090379999982717";
        WhatsAppFlow flow = WhatsAppFlow.builder()
                .metaFlowId(flowId)
                .status(FlowLifecycleStatus.PUBLISHED)
                .build();
        flow.setTenant(user.getTenant());

        when(flowRepository.findByMetaFlowIdAndTenantId(flowId, tenantId))
                .thenReturn(Optional.of(flow));

        TemplateButtonDto btn = TemplateButtonDto.builder()
                .type("FLOW")
                .text("Book Appointment")
                .flowId(flowId)
                .flowAction("navigate")
                .navigateScreen("FIRST_SCREEN")
                .build();

        assertDoesNotThrow(() -> validator.validateFlowButtons(List.of(btn), tenantId));
    }

    @Test
    @DisplayName("Rejects flow button when flow is still in DRAFT status")
    void testDraftFlowThrows() {
        String flowId = "1090379999982717";
        WhatsAppFlow flow = WhatsAppFlow.builder()
                .name("Draft Lead Flow")
                .metaFlowId(flowId)
                .status(FlowLifecycleStatus.DRAFT)
                .build();
        flow.setTenant(user.getTenant());

        when(flowRepository.findByMetaFlowIdAndTenantId(flowId, tenantId))
                .thenReturn(Optional.of(flow));

        TemplateButtonDto btn = TemplateButtonDto.builder()
                .type("FLOW")
                .text("Book Appointment")
                .flowId(flowId)
                .build();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                validator.validateFlowButtons(List.of(btn), tenantId)
        );
        assertTrue(ex.getMessage().contains("Only PUBLISHED Flows can be attached"));
    }

    @Test
    @DisplayName("Rejects flow button when flow does not belong to tenant")
    void testForeignFlowThrows() {
        String flowId = "999999999999";
        when(flowRepository.findByMetaFlowIdAndTenantId(flowId, tenantId))
                .thenReturn(Optional.empty());

        TemplateButtonDto btn = TemplateButtonDto.builder()
                .type("FLOW")
                .text("Book Appointment")
                .flowId(flowId)
                .build();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                validator.validateFlowButtons(List.of(btn), tenantId)
        );
        assertTrue(ex.getMessage().contains("does not exist or does not belong to your WhatsApp Business Account"));
    }

    @Test
    @DisplayName("Rejects multiple flow buttons in same template")
    void testMultipleFlowButtonsThrows() {
        TemplateButtonDto btn1 = TemplateButtonDto.builder().type("FLOW").text("Flow 1").flowId("1").build();
        TemplateButtonDto btn2 = TemplateButtonDto.builder().type("FLOW").text("Flow 2").flowId("2").build();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                validator.validateFlowButtons(List.of(btn1, btn2), tenantId)
        );
        assertTrue(ex.getMessage().contains("at most 1 WhatsApp Flow button"));
    }
}
