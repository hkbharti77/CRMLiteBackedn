package com.chatcrmlite.backend.services.voice.tools;

import com.chatcrmlite.backend.dto.FlowStepDTO;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.FlowConfigService;
import dev.langchain4j.agent.tool.JsonSchemaProperty;
import dev.langchain4j.agent.tool.ToolSpecification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Builds ToolSpecification objects for voice tools dynamically from
 * the same FlowConfigService that powers the WhatsApp and chat bots.
 *
 * This means:
 *   Admin panel → TenantFlowConfig (DB) → FlowConfigService
 *                                               ↓
 *                                  VoiceFormConfigAdapter
 *                                               ↓
 *                              ToolSpecification (dynamic, per-tenant)
 *                                               ↓
 *                              ConversationOrchestrator / LLM
 *
 * No more hardcoded questions in individual Tool classes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoiceFormConfigAdapter {

    private final FlowConfigService flowConfigService;
    private final UserRepository userRepository;

    /**
     * Builds a ToolSpecification for the lead capture form using
     * the tenant's configured LEAD_CAPTURE flow fields from the DB.
     */
    public ToolSpecification buildLeadToolSpec(UUID tenantId) {
        return buildSpecForFlow(
                tenantId,
                "create_lead",
                "Creates a new lead for a prospective customer. Use when the caller wants to enquire, leave their details, or request a callback.",
                "lead"
        );
    }

    /**
     * Builds a ToolSpecification for the appointment booking form.
     */
    public ToolSpecification buildAppointmentToolSpec(UUID tenantId) {
        return buildSpecForFlow(
                tenantId,
                "book_appointment",
                "Books an appointment for the caller at a specific date and time.",
                "appointment"
        );
    }

    /**
     * Builds a ToolSpecification for the booking form (classes/events).
     */
    public ToolSpecification buildBookingToolSpec(UUID tenantId) {
        return buildSpecForFlow(
                tenantId,
                "create_booking",
                "Creates a booking for an event, class, or service for the caller.",
                "booking"
        );
    }

    /**
     * Builds a ToolSpecification for the support ticket form.
     */
    public ToolSpecification buildSupportToolSpec(UUID tenantId) {
        return buildSpecForFlow(
                tenantId,
                "submit_support_ticket",
                "Submits a support ticket or records an issue raised by the caller.",
                "support"
        );
    }

    /**
     * Core builder — resolves the tenant's User, fetches their flow config
     * from FlowConfigService (same source used by WhatsApp/chat bots),
     * and maps each enabled FlowStepDTO into a ToolSpecification parameter.
     */
    private ToolSpecification buildSpecForFlow(UUID tenantId, String toolName, String toolDescription, String flowType) {
        User owner = userRepository.findById(tenantId).orElse(null);

        ToolSpecification.Builder builder = ToolSpecification.builder()
                .name(toolName)
                .description(toolDescription);

        if (owner == null) {
            log.warn("[VoiceFormAdapter] Owner not found for tenantId={}, returning bare spec for tool={}", tenantId, toolName);
            // Return a minimal fallback spec with just customer_name
            return builder
                    .addParameter("customer_name", JsonSchemaProperty.STRING,
                            JsonSchemaProperty.description("The name of the caller."))
                    .build();
        }

        try {
            // Load the SAME config that WhatsApp/chat bots use
            var flowConfig = flowConfigService.getFlowConfig(owner, flowType);

            if (flowConfig == null || flowConfig.getSteps() == null || flowConfig.getSteps().isEmpty()) {
                log.warn("[VoiceFormAdapter] No steps found for tenantId={} flowType={}, using fallback", tenantId, flowType);
                return buildFallbackSpec(builder, flowType);
            }

            List<FlowStepDTO> steps = flowConfig.getSteps();
            log.info("[VoiceFormAdapter] Building voice ToolSpec for tool={} with {} fields from DB for tenant={}",
                    toolName, steps.size(), tenantId);

            for (FlowStepDTO step : steps) {
                if (step.getDataKey() == null || step.getDataKey().isBlank()) continue;

                // Skip phone — it is auto-captured from the Exotel call metadata
                if ("phone".equals(step.getDataKey()) || "mobile".equals(step.getDataKey())
                        || "whatsapp".equals(step.getDataKey())) {
                    continue;
                }

                String paramName = step.getDataKey();
                String description = buildParamDescription(step);

                // Mark optional vs required in the description for LLM guidance
                if (!step.isRequired()) {
                    description += " (optional)";
                }

                builder.addParameter(paramName, JsonSchemaProperty.STRING,
                        JsonSchemaProperty.description(description));
            }

        } catch (Exception e) {
            log.error("[VoiceFormAdapter] Error building ToolSpec for tool={} tenant={}: {}",
                    toolName, tenantId, e.getMessage());
            return buildFallbackSpec(builder, flowType);
        }

        return builder.build();
    }

    /**
     * Constructs a human-readable parameter description from a FlowStepDTO.
     * Used to guide the LLM on what question to ask the caller.
     */
    private String buildParamDescription(FlowStepDTO step) {
        // step.question is the admin-configured question text (e.g. "What is your name?")
        // This is the same question shown in the WhatsApp/chat flow
        String base = step.getQuestion() != null && !step.getQuestion().isBlank()
                ? step.getQuestion()
                : "The value for " + step.getDataKey().replace("_", " ");

        // If the field has static options, hint the LLM to offer them
        if (step.getOptions() != null && !step.getOptions().isEmpty() && !step.isDynamicSource()) {
            base += " Options: " + String.join(", ", step.getOptions()) + ".";
        }

        return base;
    }

    /**
     * Returns a minimal hardcoded fallback spec when the DB has no config.
     * This matches the previous hardcoded behaviour but only fires as a last resort.
     */
    private ToolSpecification buildSpecForFlow(UUID tenantId, String toolName, String toolDesc) {
        return buildSpecForFlow(tenantId, toolName, toolDesc, "lead");
    }

    private ToolSpecification buildFallbackSpec(ToolSpecification.Builder builder, String flowType) {
        switch (flowType) {
            case "appointment":
                return builder
                        .addParameter("customer_name", JsonSchemaProperty.STRING,
                                JsonSchemaProperty.description("The name of the caller."))
                        .addParameter("service", JsonSchemaProperty.STRING,
                                JsonSchemaProperty.description("The service or reason for the appointment."))
                        .addParameter("date", JsonSchemaProperty.STRING,
                                JsonSchemaProperty.description("The preferred date (YYYY-MM-DD)."))
                        .addParameter("time", JsonSchemaProperty.STRING,
                                JsonSchemaProperty.description("The preferred time (HH:mm 24-hour)."))
                        .build();
            case "booking":
                return builder
                        .addParameter("customer_name", JsonSchemaProperty.STRING,
                                JsonSchemaProperty.description("The name of the caller."))
                        .addParameter("service", JsonSchemaProperty.STRING,
                                JsonSchemaProperty.description("The service to book."))
                        .addParameter("preferred_slot", JsonSchemaProperty.STRING,
                                JsonSchemaProperty.description("The preferred date/time slot."))
                        .build();
            case "support":
                return builder
                        .addParameter("customer_name", JsonSchemaProperty.STRING,
                                JsonSchemaProperty.description("The name of the caller."))
                        .addParameter("issue_description", JsonSchemaProperty.STRING,
                                JsonSchemaProperty.description("A detailed description of the caller's issue."))
                        .build();
            default: // lead
                return builder
                        .addParameter("customer_name", JsonSchemaProperty.STRING,
                                JsonSchemaProperty.description("The name of the caller."))
                        .addParameter("customer_email", JsonSchemaProperty.STRING,
                                JsonSchemaProperty.description("The email of the caller (optional)."))
                        .addParameter("enquiry_details", JsonSchemaProperty.STRING,
                                JsonSchemaProperty.description("What the caller is enquiring about."))
                        .build();
        }
    }
}
