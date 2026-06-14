package com.chatcrmlite.backend.services.flow;

import com.chatcrmlite.backend.dto.FlowConfigDTO;
import com.chatcrmlite.backend.dto.FlowStepDTO;
import com.chatcrmlite.backend.dto.flow.FlowMachineDef;
import com.chatcrmlite.backend.dto.flow.StateDef;
import com.chatcrmlite.backend.dto.flow.TransitionDef;
import com.chatcrmlite.backend.models.ConversationState;
import com.chatcrmlite.backend.models.FlowDefinition;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.FlowDefinitionRepository;
import com.chatcrmlite.backend.services.FlowConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FlowDefinitionLoader {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FlowDefinitionLoader.class);

    private final FlowDefinitionRepository flowDefinitionRepository;
    private final FlowConfigService flowConfigService;
    private final ObjectMapper objectMapper;

    /**
     * Loads the parsed FlowMachineDef for a given FlowDefinition entity (DB-backed).
     * Caches the result based on the flowDefinitionId.
     */
    @Cacheable(value = "flow_machine_defs", key = "#flowDefinitionId")
    public FlowMachineDef loadDefinition(UUID flowDefinitionId) {
        FlowDefinition def = flowDefinitionRepository.findById(flowDefinitionId)
                .orElseThrow(() -> new IllegalArgumentException("FlowDefinition not found for id: " + flowDefinitionId));
        return parseDefinition(def.getDefinitionJson());
    }

    /**
     * Resolves a FlowMachineDef for a tenant without needing a DB-stored FlowDefinition.
     * Priority:
     *   1. Tenant-specific DB row
     *   2. Global DB row
     *   3. Niche JSON file from classpath (resources/flows/<slug>.json)
     *   4. Generic JSON file (resources/flows/generic.json)
     *
     * Returns empty if nothing is found at all.
     */
    public Optional<FlowMachineDef> resolveFlowMachineDef(User owner) {
        return resolveFlowMachineDef(owner, null);
    }

    public Optional<FlowMachineDef> resolveFlowMachineDef(User owner, String explicitSuffix) {
        // 1 & 2 — check DB first (tenant-scoped, then global)
        for (ConversationState.FlowType type : ConversationState.FlowType.values()) {
            Optional<FlowDefinition> dbDef = flowDefinitionRepository
                    .findLatestActiveByTenantAndFlowType(owner, type);
            if (dbDef.isEmpty()) {
                dbDef = flowDefinitionRepository.findLatestActiveGlobalByFlowType(type);
            }
            if (dbDef.isPresent()) {
                log.debug("[FlowLoader] Using DB flow definition for owner={}", owner.getId());
                return Optional.of(parseDefinition(dbDef.get().getDefinitionJson()));
            }
        }

        // 3 & 4 — fall back to classpath JSON files
        FlowConfigDTO config = flowConfigService.getFlowConfig(owner, explicitSuffix);
        if (config == null || config.getSteps() == null || config.getSteps().isEmpty()) {
            log.warn("[FlowLoader] No flow config found for owner={}", owner.getId());
            return Optional.empty();
        }

        log.debug("[FlowLoader] Building FlowMachineDef from JSON file for owner={}, flowType={}",
                owner.getId(), config.getFlowType());
        return Optional.of(buildMachineDefFromSteps(config));
    }

    /**
     * Converts the legacy steps-array format (FlowConfigDTO) into a FlowMachineDef
     * state machine. Each step becomes a MESSAGE state chained sequentially.
     *
     * Step 0  → STATE_0  → STATE_1 → ... → STATE_N → COMPLETE (END)
     */
    public FlowMachineDef buildMachineDefFromSteps(FlowConfigDTO config) {
        List<FlowStepDTO> steps = config.getSteps();
        Map<String, StateDef> states = new LinkedHashMap<>();

        for (int i = 0; i < steps.size(); i++) {
            FlowStepDTO step = steps.get(i);
            String stateName = "STATE_" + i;
            String nextState = (i < steps.size() - 1) ? "STATE_" + (i + 1) : "COMPLETE";

            StateDef state = StateDef.builder()
                    .type(StateDef.StateType.MESSAGE)
                    .text(step.getQuestion())
                    .saveInputAs(step.getDataKey())
                    .options(step.getOptions() != null && !step.getOptions().isEmpty()
                            ? step.getOptions() : null)
                    .dynamicOptions(step.isDynamicSource())
                    .transitions(List.of(TransitionDef.builder().target(nextState).build()))
                    .fallbackState(stateName)
                    .build();

            states.put(stateName, state);
        }

        // Terminal state
        states.put("COMPLETE", StateDef.builder()
                .type(StateDef.StateType.END)
                .text("✅ Thank you! We have received your details and will be in touch shortly.")
                .build());

        return FlowMachineDef.builder()
                .initialState("STATE_0")
                .states(states)
                .build();
    }

    /**
     * Gets the latest active FlowDefinition (DB row) for a tenant and flow type.
     * Falls back to global. Returns empty Optional if nothing in DB.
     */
    public Optional<FlowDefinition> findLatestActiveDefinition(User tenant, ConversationState.FlowType flowType) {
        Optional<FlowDefinition> tenantDef = flowDefinitionRepository
                .findLatestActiveByTenantAndFlowType(tenant, flowType);
        if (tenantDef.isPresent()) return tenantDef;
        return flowDefinitionRepository.findLatestActiveGlobalByFlowType(flowType);
    }

    /**
     * Gets the latest active FlowDefinition (DB row). Throws if not found.
     */
    public FlowDefinition getLatestActiveDefinition(User tenant, ConversationState.FlowType flowType) {
        return findLatestActiveDefinition(tenant, flowType)
                .orElseThrow(() -> new IllegalStateException(
                        "No active flow definition found for type: " + flowType));
    }

    /**
     * Exposes FlowConfigService for callers that need to resolve flow type from JSON config.
     */
    public FlowConfigService getFlowConfigService() {
        return flowConfigService;
    }

    private FlowMachineDef parseDefinition(String json) {
        try {
            return objectMapper.readValue(json, FlowMachineDef.class);
        } catch (Exception e) {
            log.error("Failed to parse FlowMachineDef JSON", e);
            throw new RuntimeException("Invalid FlowMachineDef JSON", e);
        }
    }
}
