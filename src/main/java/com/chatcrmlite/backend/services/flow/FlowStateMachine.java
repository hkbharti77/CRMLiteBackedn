package com.chatcrmlite.backend.services.flow;

import com.chatcrmlite.backend.dto.flow.FlowMachineDef;
import com.chatcrmlite.backend.dto.flow.StateDef;
import com.chatcrmlite.backend.flow.FlowContext;
import com.chatcrmlite.backend.flow.FlowHandler;
import com.chatcrmlite.backend.flow.FlowResponse;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.ConversationState;
import com.chatcrmlite.backend.models.FlowDefinition;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.repositories.ConversationStateRepository;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import com.chatcrmlite.backend.services.whatsapp.WhatsAppOutboundService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FlowStateMachine {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FlowStateMachine.class);

    private final ConversationStateRepository stateRepository;
    private final FlowDefinitionLoader definitionLoader;
    private final TransitionEngine transitionEngine;
    private final StateResolver stateResolver;
    private final ObjectMapper objectMapper;
    private final List<FlowHandler> flowHandlers; // Legacy support for completion
    private final WhatsAppOutboundService outboundService;
    private final WhatsAppConfigRepository configRepository;

    @Transactional
    public boolean processFlow(Contact contact, User owner, String messageText, String selectionId, boolean isInteractiveSelection) {
        Optional<ConversationState> existingStateOpt = stateRepository.findByContact(contact);

        if (existingStateOpt.isPresent()) {
            ConversationState state = existingStateOpt.get();
            log.debug("[StateMachine] Processing flow for contact={}, currentState={}, input={}", 
                    contact.getWaId(), state.getCurrentState(), messageText);

            // Pagination handling for dynamic lists
            if (isInteractiveSelection && selectionId != null && selectionId.startsWith("flow_page_")) {
                int nextPg = Integer.parseInt(selectionId.replace("flow_page_", ""));
                FlowMachineDef machineDef = loadMachineDefForState(state, owner);
                StateDef currentStateDef = machineDef.getStates().get(state.getCurrentState());
                log.debug("[StateMachine] Pagination request for state={}, page={}", state.getCurrentState(), nextPg);
                stateResolver.sendStateMessage(currentStateDef, contact, owner, nextPg);
                return true;
            }

            advanceFlow(state, contact, owner, messageText, selectionId);
            return true;
        }

        // Check if starting a new flow
        if (isInteractiveSelection && selectionId != null && selectionId.startsWith("trigger_flow")) {
            log.debug("[StateMachine] Starting new flow for contact={}", contact.getWaId());
            String suffix = null;
            if (selectionId.startsWith("trigger_flow_")) {
                suffix = selectionId.substring("trigger_flow_".length());
            }
            return startFlow(contact, owner, messageText, suffix);
        }

        return false;
    }

    public boolean startFlow(Contact contact, User owner, String initialMessage) {
        return startFlow(contact, owner, initialMessage, null);
    }

    /**
     * Starts a flow for the given owner. Resolution priority:
     *   1. Tenant-specific DB FlowDefinition
     *   2. Global DB FlowDefinition
     *   3. Niche JSON file (resources/flows/<slug>.json)
     *   4. Generic JSON file (resources/flows/generic.json)
     */
    public boolean startFlow(Contact contact, User owner, String initialMessage, String flowSuffix) {
        ConversationState.FlowType flowType = resolveFlowTypeForOwner(owner, flowSuffix);

        // Try DB-backed definition first for THIS SPECIFIC flowType
        Optional<FlowDefinition> dbDefOpt = definitionLoader.findLatestActiveDefinition(owner, flowType);

        FlowMachineDef machineDef;
        UUID flowDefinitionId = null;

        if (dbDefOpt.isPresent()) {
            FlowDefinition def = dbDefOpt.get();
            flowDefinitionId = def.getId();
            flowType = def.getFlowType();
            machineDef = definitionLoader.loadDefinition(flowDefinitionId);
            // FIX #4: Validate machineDef is not null after loading
            if (machineDef == null) {
                log.error("[StateMachine] Failed to load DB flow definition id={} for owner={}", flowDefinitionId, owner.getId());
                return false;
            }
            log.debug("[StateMachine] Using DB flow definition id={} for owner={}", flowDefinitionId, owner.getId());
        } else {
            // Fall back to classpath JSON files via FlowConfigService
            Optional<FlowMachineDef> jsonDef = definitionLoader.resolveFlowMachineDef(owner, flowSuffix);
            if (jsonDef.isEmpty()) {
                log.warn("[StateMachine] No flow definition found for owner={} — skipping flow start", owner.getId());
                return false;
            }
            machineDef = jsonDef.get();
            // FIX #4: Validate machineDef is not null
            if (machineDef == null) {
                log.error("[StateMachine] Resolved flow definition is null for owner={}", owner.getId());
                return false;
            }
            // flowType is already resolved
            log.debug("[StateMachine] Using JSON file flow for owner={}, flowType={}", owner.getId(), flowType);
        }

        String initialStateName = machineDef.getInitialState();
        // FIX #4: Validate initial state name exists
        if (initialStateName == null || initialStateName.isBlank()) {
            log.error("[StateMachine] Flow definition has no initial state for owner={}", owner.getId());
            return false;
        }

        ConversationState state = ConversationState.builder()
                .contact(contact)
                .flowType(flowType)
                .flowDefinitionId(flowDefinitionId) // null for JSON-backed flows
                .currentState(initialStateName)
                .collectedData("{}")
                .build();

        saveAnswer(state, "initial_selection", initialMessage);
        // CRITICAL: Save state to DB BEFORE executing the first step.
        // Without this, the next incoming message finds no active flow and falls back to MENU.
        stateRepository.save(state);
        executeState(state, machineDef, contact, owner);
        return true;
    }

    /** Kept for backward compatibility — delegates to startFlow(contact, owner, initialMessage). */
    public boolean startFlow(Contact contact, User owner, ConversationState.FlowType flowType, String initialMessage) {
        return startFlow(contact, owner, initialMessage);
    }

    private Optional<FlowDefinition> findDbDefinitionForOwner(User owner) {
        for (ConversationState.FlowType type : ConversationState.FlowType.values()) {
            Optional<FlowDefinition> def = definitionLoader.findLatestActiveDefinition(owner, type);
            if (def.isPresent()) return def;
        }
        return Optional.empty();
    }

    private ConversationState.FlowType resolveFlowTypeForOwner(User owner, String flowSuffix) {
        try {
            com.chatcrmlite.backend.dto.FlowConfigDTO config =
                    definitionLoader.getFlowConfigService().getFlowConfig(owner, flowSuffix);
            if (config != null && config.getFlowType() != null) {
                return ConversationState.FlowType.valueOf(config.getFlowType().toUpperCase());
            }
        } catch (Exception e) {
            log.warn("[StateMachine] Could not resolve flowType for owner={}: {}", owner.getId(), e.getMessage());
        }
        return ConversationState.FlowType.ENQUIRY;
    }

    /**
     * Loads the FlowMachineDef for an in-progress conversation state.
     * If the state has a DB-backed flowDefinitionId, loads from DB.
     * Otherwise re-resolves from JSON files (for flows started before DB rows existed).
     */
    private FlowMachineDef loadMachineDefForState(ConversationState state, User owner) {
        if (state.getFlowDefinitionId() != null) {
            return definitionLoader.loadDefinition(state.getFlowDefinitionId());
        }
        return definitionLoader.resolveFlowMachineDef(owner, state.getFlowType().name().toLowerCase())
                .orElseThrow(() -> new IllegalStateException(
                        "Cannot reload flow definition for in-progress state, owner=" + owner.getId()));
    }

    private void advanceFlow(ConversationState state, Contact contact, User owner, String input, String selectionId) {
        FlowMachineDef machineDef = loadMachineDefForState(state, owner);
        StateDef currentStateDef = machineDef.getStates().get(state.getCurrentState());

        // Use selectionId if interactive, otherwise plain text input
        String activeInput = (selectionId != null && !selectionId.isEmpty() && !selectionId.startsWith("flow_page_")) ? selectionId : input;
        
        // Save input if the state requires it
        if (currentStateDef.getSaveInputAs() != null) {
            String field = currentStateDef.getSaveInputAs();
            String errorMsg = validateInput(field, activeInput);
            if (errorMsg != null) {
                log.warn("[StateMachine] Validation failed for field {}: {}", field, errorMsg);
                WhatsAppConfig config = configRepository.findByUserId(owner.getId()).orElse(null);
                if (config != null) {
                    try {
                        outboundService.sendText(contact, "⚠️ " + errorMsg, config, owner);
                    } catch (Exception e) {
                        log.warn("[StateMachine] Could not send validation warning: {}", e.getMessage());
                    }
                }
                // Resend the question
                stateResolver.sendStateMessage(currentStateDef, contact, owner, 0);
                return;
            }
            saveAnswer(state, field, activeInput);
        }

        // Build context for transitions
        FlowContext context = FlowContext.builder()
                .contact(contact)
                .owner(owner)
                .flowType(state.getFlowType())
                .collectedData(parseData(state.getCollectedData()))
                .build();

        String nextStateName = transitionEngine.evaluateNextState(currentStateDef, activeInput, context);

        if (nextStateName == null) {
            log.warn("[StateMachine] No valid transition found from state {} for input {}", state.getCurrentState(), activeInput);
            // Re-send current state message as a fallback (user sent invalid/unexpected input)
            WhatsAppConfig config = configRepository.findByUserId(owner.getId()).orElse(null);
            if (config != null && currentStateDef.getOptions() != null && !currentStateDef.getOptions().isEmpty()) {
                // Prompt the user to pick from the available options
                try {
                    outboundService.sendText(contact, "Please choose one of the options provided below:", config, owner);
                } catch (Exception e) {
                    log.warn("[StateMachine] Could not send validation hint to {}: {}", contact.getWaId(), e.getMessage());
                }
            }
            stateResolver.sendStateMessage(currentStateDef, contact, owner, 0);
            return;
        }

        // Transition to next state
        transitionToState(state, nextStateName, machineDef, contact, owner);
    }

    private void transitionToState(ConversationState state, String nextStateName, FlowMachineDef machineDef, Contact contact, User owner) {
        StateDef nextStateDef = machineDef.getStates().get(nextStateName);

        if (nextStateDef == null) {
            log.error("[StateMachine] Target state {} does not exist in flow definition {}", nextStateName, state.getFlowDefinitionId());
            return;
        }

        log.info("[StateMachine] Transitioning contact={} from state={} to state={}", 
                contact.getWaId(), state.getCurrentState(), nextStateName);

        // Update state and history
        state.setCurrentState(nextStateName);
        appendHistory(state, nextStateName);

        if (nextStateDef.getType() == StateDef.StateType.END) {
            log.info("[StateMachine] Flow completed for contact={}, deleting state", contact.getWaId());
            stateRepository.delete(state);
            completeFlow(state, contact, owner, state.getFlowType());
        } else {
            stateRepository.save(state);
            log.debug("[StateMachine] State saved for contact={}, executing state={}", contact.getWaId(), nextStateName);
            executeState(state, machineDef, contact, owner);
        }
    }

    private String validateInput(String field, String input) {
        if (input == null || input.trim().isEmpty()) {
            return "Please provide a valid answer.";
        }
        String val = input.trim();
        
        if ("email".equalsIgnoreCase(field)) {
            if (val.length() > 256) {
                return "Email address is too long. Maximum allowed length is 256 characters.";
            }
            String emailRegex = "^[A-Za-z0-9+_.-]+@(.+)$";
            if (!val.matches(emailRegex)) {
                return "That doesn't look like a valid email address. Please enter a valid email (e.g., name@example.com).";
            }
        } else if ("phone".equalsIgnoreCase(field)) {
            String phoneRegex = "^\\+?[0-9]{7,15}$";
            // Strip spaces, dashes, parentheses to check digits
            if (!val.replaceAll("[\\s\\-\\(\\)]", "").matches(phoneRegex)) {
                return "That doesn't look like a valid phone number. Please enter a valid number (e.g., +1234567890).";
            }
        } else if ("name".equalsIgnoreCase(field)) {
            if (val.length() < 2) {
                return "Name is too short. Please enter your full name (minimum 2 characters).";
            }
            if (val.length() > 67) {
                return "Name is too long. Maximum allowed length is 67 characters.";
            }
        }
        
        return null;
    }

    private void executeState(ConversationState state, FlowMachineDef machineDef, Contact contact, User owner) {
        StateDef stateDef = machineDef.getStates().get(state.getCurrentState());
        
        if (stateDef.getType() == StateDef.StateType.MESSAGE) {
            stateResolver.sendStateMessage(stateDef, contact, owner, 0);
        } else if (stateDef.getType() == StateDef.StateType.EVALUATE) {
            // Auto transition immediately
            FlowContext context = FlowContext.builder()
                    .contact(contact)
                    .owner(owner)
                    .flowType(state.getFlowType())
                    .collectedData(parseData(state.getCollectedData()))
                    .build();
            String nextStateName = transitionEngine.evaluateNextState(stateDef, null, context);
            if (nextStateName != null) {
                transitionToState(state, nextStateName, machineDef, contact, owner);
            } else {
                log.error("[StateMachine] EVALUATE state {} yielded no next state", state.getCurrentState());
            }
        }
    }

    private void completeFlow(ConversationState state, Contact contact, User owner, ConversationState.FlowType flowType) {
        Map<String, String> data = parseData(state.getCollectedData());
        log.info("[StateMachine] Flow completed for contact={}, flowType={}, collected data keys: {}", 
                contact.getWaId(), flowType, data.keySet());
        log.debug("[StateMachine] Flow completed with full data: {}", data);
        
        WhatsAppConfig config = configRepository.findByUserId(owner.getId()).orElse(null);

        FlowContext context = FlowContext.builder()
                .contact(contact)
                .owner(owner)
                .flowType(flowType)
                .collectedData(data)
                .build();

        FlowResponse response = flowHandlers.stream()
                .filter(h -> h.supports(flowType))
                .findFirst()
                .map(h -> h.handle(context))
                .orElseGet(() -> FlowResponse.failure("No handler for flow type: " + flowType));

        if (!response.isSuccess()) {
            log.error("[StateMachine] FlowHandler failed for flowType={}: {}", flowType, response.getErrorReason());
        }

        if (config != null && response.getConfirmationMessage() != null) {
            try {
                outboundService.sendText(contact, response.getConfirmationMessage(), config, owner);
            } catch (Exception e) {
                log.warn("[StateMachine] Could not send confirmation to {}: {}", contact.getWaId(), e.getMessage());
            }
        }
    }

    private void saveAnswer(ConversationState state, String key, String value) {
        Map<String, String> data = parseData(state.getCollectedData());
        data.put(key, value);
        log.debug("[StateMachine] Saving answer: key='{}', value='{}', current data keys: {}", key, value, data.keySet());
        try {
            state.setCollectedData(objectMapper.writeValueAsString(data));
        } catch (Exception e) {
            // FIX #15: Throw exception to trigger transaction rollback instead of silently failing
            log.error("[StateMachine] Failed to serialize data for key={}: {}", key, e.getMessage(), e);
            throw new IllegalStateException("Failed to save flow data: " + e.getMessage(), e);
        }
    }

    private void appendHistory(ConversationState state, String stateName) {
        try {
            List<String> history = objectMapper.readValue(state.getStateHistory(), new TypeReference<>() {});
            history.add(stateName);
            state.setStateHistory(objectMapper.writeValueAsString(history));
        } catch (Exception e) {
            // FIX #15: Throw exception to trigger transaction rollback instead of silently failing
            log.error("[StateMachine] Failed to serialize state history for state={}: {}", stateName, e.getMessage(), e);
            throw new IllegalStateException("Failed to update state history: " + e.getMessage(), e);
        }
    }

    private Map<String, String> parseData(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    @Transactional
    public void resetFlow(Contact contact) {
        stateRepository.deleteByContact(contact);
    }

    @Scheduled(fixedDelay = 3_600_000) // every 1 hour
    @SchedulerLock(name = "FlowStateMachine_cleanupStaleFlows", lockAtMostFor = "50m", lockAtLeastFor = "10m")
    @Transactional
    public void cleanupStaleFlows() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(24);
        stateRepository.deleteStaleFlows(cutoff);
        log.info("[StateMachine] Stale flow cleanup completed (cutoff={})", cutoff);
    }
}
