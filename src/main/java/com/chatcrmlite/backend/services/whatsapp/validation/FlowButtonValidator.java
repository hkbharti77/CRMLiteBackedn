package com.chatcrmlite.backend.services.whatsapp.validation;

import com.chatcrmlite.backend.dto.WhatsAppTemplateDto;
import com.chatcrmlite.backend.models.flows.FlowLifecycleStatus;
import com.chatcrmlite.backend.models.flows.WhatsAppFlow;
import com.chatcrmlite.backend.repositories.flows.WhatsAppFlowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Enterprise Flow Button Validator.
 *
 * Enforces strict multi-tenant isolation and Meta compliance:
 * 1. Flow button limit (max 1 per template)
 * 2. Button label limits (max 25 characters)
 * 3. Flow ownership check against authenticated tenant's WABA
 * 4. Flow lifecycle status verification (must be PUBLISHED on Meta)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FlowButtonValidator {

    private final WhatsAppFlowRepository flowRepository;

    private static final int MAX_FLOW_BUTTONS = 1;
    private static final int MAX_BUTTON_TEXT_LENGTH = 25;
    private static final List<String> ALLOWED_FLOW_ACTIONS = List.of("navigate", "data_exchange");

    /**
     * Validates all buttons in a template for Flow compliance.
     *
     * @param buttons List of template buttons
     * @param tenantId The authenticated tenant ID
     */
    public void validateFlowButtons(List<WhatsAppTemplateDto.TemplateButtonDto> buttons, UUID tenantId) {
        if (buttons == null || buttons.isEmpty()) {
            return;
        }

        long flowButtonCount = buttons.stream()
                .filter(b -> b != null && "FLOW".equalsIgnoreCase(b.getType()))
                .count();

        if (flowButtonCount > MAX_FLOW_BUTTONS) {
            throw new IllegalArgumentException(
                    String.format("A message template can contain at most %d WhatsApp Flow button(s). Found %d.",
                            MAX_FLOW_BUTTONS, flowButtonCount));
        }

        for (WhatsAppTemplateDto.TemplateButtonDto btn : buttons) {
            if (btn != null && "FLOW".equalsIgnoreCase(btn.getType())) {
                validateSingleFlowButton(btn, tenantId);
            }
        }
    }

    private void validateSingleFlowButton(WhatsAppTemplateDto.TemplateButtonDto btn, UUID tenantId) {
        if (!StringUtils.hasText(btn.getText())) {
            throw new IllegalArgumentException("Flow button requires a label (text).");
        }
        if (btn.getText().trim().length() > MAX_BUTTON_TEXT_LENGTH) {
            throw new IllegalArgumentException(
                    String.format("Flow button label cannot exceed %d characters. Current: %d",
                            MAX_BUTTON_TEXT_LENGTH, btn.getText().trim().length()));
        }

        if (!StringUtils.hasText(btn.getFlowId())) {
            throw new IllegalArgumentException("Flow button requires a valid flowId.");
        }

        String flowIdentifier = btn.getFlowId().trim();

        // Validate action
        String action = StringUtils.hasText(btn.getFlowAction()) ? btn.getFlowAction().trim().toLowerCase() : "navigate";
        if (!ALLOWED_FLOW_ACTIONS.contains(action)) {
            throw new IllegalArgumentException(
                    "Unsupported flow action: '" + action + "'. Allowed: " + String.join(", ", ALLOWED_FLOW_ACTIONS));
        }

        // Security check: Verify Flow belongs strictly to the authenticated tenant
        Optional<WhatsAppFlow> flowOpt = flowRepository.findByMetaFlowIdAndTenantId(flowIdentifier, tenantId);
        if (flowOpt.isEmpty()) {
            // Also try resolving by internal UUID in case client passed internal ID
            try {
                UUID internalId = UUID.fromString(flowIdentifier);
                flowOpt = flowRepository.findByIdAndTenantId(internalId, tenantId);
            } catch (IllegalArgumentException ignored) {}
        }

        if (flowOpt.isEmpty()) {
            log.warn("[Security] Flow injection blocked: Flow {} does not exist for tenant {}", flowIdentifier, tenantId);
            throw new IllegalArgumentException(
                    "WhatsApp Flow '" + flowIdentifier + "' does not exist or does not belong to your WhatsApp Business Account.");
        }

        WhatsAppFlow flow = flowOpt.get();

        // Enforce published status
        if (flow.getStatus() != FlowLifecycleStatus.PUBLISHED) {
            log.warn("[Security] Flow rejected: Flow {} is in {} state (expected PUBLISHED)", flow.getId(), flow.getStatus());
            throw new IllegalArgumentException(
                    "WhatsApp Flow '" + flow.getName() + "' cannot be attached because it is in " +
                            flow.getStatus() + " status. Only PUBLISHED Flows can be attached to templates.");
        }

        // Ensure metaFlowId is normalized on the button
        if (flow.getMetaFlowId() != null) {
            btn.setFlowId(flow.getMetaFlowId());
        }
    }
}
