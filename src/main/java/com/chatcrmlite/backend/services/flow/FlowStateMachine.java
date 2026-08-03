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
        // 1. Check if starting a new flow FIRST — overrides any existing active state
        if (isInteractiveSelection && selectionId != null && selectionId.startsWith("trigger_flow")) {
            log.debug("[StateMachine] Starting new flow for contact={}", contact.getWaId());
            String suffix = null;
            if (selectionId.startsWith("trigger_flow_")) {
                suffix = selectionId.substring("trigger_flow_".length());
            }
            return startFlow(contact, owner, messageText, suffix);
        }

        Optional<ConversationState> existingStateOpt = stateRepository.findByContact(contact);

        if (existingStateOpt.isPresent()) {
            ConversationState state = existingStateOpt.get();
            log.debug("[StateMachine] Processing flow for contact={}, currentState={}, input={}", 
                    contact.getWaId(), state.getCurrentState(), messageText);

            // Pagination handling for dynamic lists
            if (isInteractiveSelection && selectionId != null && selectionId.startsWith("flow_page_")) {
                int nextPg = Integer.parseInt(selectionId.replace("flow_page_", ""));
                Optional<FlowMachineDef> machineDefOpt = loadMachineDefForState(state, owner);
                if (machineDefOpt.isEmpty()) {
                    log.error("[StateMachine] Cannot paginate — flow definition missing for contact={}, owner={}",
                            contact.getWaId(), owner.getId());
                    return false;
                }
                StateDef currentStateDef = machineDefOpt.get().getStates().get(state.getCurrentState());
                if (currentStateDef == null) {
                    log.warn("[StateMachine] State definition {} not found. Deleting stale ConversationState for contact={}",
                            state.getCurrentState(), contact.getWaId());
                    stateRepository.delete(state);
                    return false;
                }
                log.debug("[StateMachine] Pagination request for state={}, page={}", state.getCurrentState(), nextPg);
                stateResolver.sendStateMessage(currentStateDef, contact, owner, nextPg);
                return true;
            }

            advanceFlow(state, contact, owner, messageText, selectionId);
            return true;
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
        resetFlow(contact);
        ConversationState.FlowType flowType = resolveFlowTypeForOwner(owner, flowSuffix);

        // Load flow config ONCE — reused for greeting and passed to flow machine builder
        com.chatcrmlite.backend.dto.FlowConfigDTO flowConfig = null;
        try {
            flowConfig = definitionLoader.getFlowConfigService().getFlowConfig(owner, flowSuffix);
        } catch (Exception e) {
            log.warn("[StateMachine] Could not load flow config for owner={}: {}", owner.getId(), e.getMessage());
        }

        // Send greeting message BEFORE starting flow questions (Admin custom greeting or flow default)
        String greeting = (flowConfig != null && flowConfig.getGreetingMessage() != null && !flowConfig.getGreetingMessage().isBlank())
                ? flowConfig.getGreetingMessage()
                : getDefaultGreetingForFlow(flowType);

        if (greeting != null && !greeting.isBlank()) {
            try {
                WhatsAppConfig waConfig = configRepository.findByUserId(owner.getId())
                        .orElseGet(() -> owner.getTenant() != null 
                                ? configRepository.findByTenantId(owner.getTenant().getId()).orElse(null) 
                                : null);
                if (waConfig != null) {
                    if (contact.getName() != null && !contact.getName().isBlank()
                            && !contact.getName().startsWith("WhatsApp User")) {
                        String firstName = contact.getName().split(" ")[0];
                        greeting = greeting.replace("{{contact.firstName}}", firstName);
                        greeting = greeting.replace("{{contact.name}}", contact.getName());
                    } else {
                        greeting = greeting.replace("{{contact.firstName}}", "there");
                        greeting = greeting.replace("{{contact.name}}", "there");
                    }
                    outboundService.sendText(contact, greeting, waConfig, owner);
                    log.info("[StateMachine] Sent greeting to contact={} for flowType={}: {}", contact.getWaId(), flowType, greeting);
                } else {
                    log.warn("[StateMachine] WhatsAppConfig null for owner={} (tenant={}) — cannot send flow greeting", owner.getId(), owner.getTenant() != null ? owner.getTenant().getId() : "null");
                }
            } catch (Exception e) {
                log.warn("[StateMachine] Could not send greeting for owner={}: {}", owner.getId(), e.getMessage());
            }
        }

        // Build machine def directly from already-loaded config (avoids 2nd DB call)
        Optional<FlowMachineDef> jsonDef;
        if (flowConfig != null && flowConfig.getSteps() != null && !flowConfig.getSteps().isEmpty()) {
            jsonDef = Optional.of(definitionLoader.buildMachineDefFromSteps(flowConfig));
        } else {
            jsonDef = definitionLoader.resolveFlowMachineDef(owner, flowSuffix);
        }
        
        if (jsonDef.isEmpty()) {
            log.warn("[StateMachine] No flow definition found for owner={} — skipping flow start", owner.getId());
            return false;
        }

        FlowMachineDef machineDef = jsonDef.get();
        UUID flowDefinitionId = machineDef.getId();

        String initialStateName = machineDef.getInitialState();
        if (initialStateName == null || initialStateName.isBlank()) {
            log.error("[StateMachine] Flow definition has no initial state for owner={}", owner.getId());
            return false;
        }

        ConversationState state = ConversationState.builder()
                .contact(contact)
                .flowType(flowType)
                .flowDefinitionId(flowDefinitionId)
                .currentState(initialStateName)
                .collectedData("{}")
                .build();

        saveAnswer(state, "initial_selection", initialMessage);
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
     * Returns empty Optional if no definition can be found (instead of throwing).
     */
    private Optional<FlowMachineDef> loadMachineDefForState(ConversationState state, User owner) {
        if (state.getFlowDefinitionId() != null) {
            try {
                return Optional.ofNullable(definitionLoader.loadDefinition(state.getFlowDefinitionId()));
            } catch (Exception e) {
                log.error("[StateMachine] Failed to load DB flow definition id={} for owner={}: {}",
                        state.getFlowDefinitionId(), owner.getId(), e.getMessage());
                return Optional.empty();
            }
        }
        return definitionLoader.resolveFlowMachineDef(owner, state.getFlowType().name().toLowerCase());
    }

    private void advanceFlow(ConversationState state, Contact contact, User owner, String input, String selectionId) {
        Optional<FlowMachineDef> machineDefOpt = loadMachineDefForState(state, owner);
        if (machineDefOpt.isEmpty()) {
            log.error("[StateMachine] Flow definition missing for in-progress state. contact={}, owner={}, flowType={}",
                    contact.getWaId(), owner.getId(), state.getFlowType());
            // Clean up the orphaned state and complete flow gracefully
            stateRepository.delete(state);
            completeFlow(state, contact, owner, state.getFlowType());
            return;
        }
        FlowMachineDef machineDef = machineDefOpt.get();
        StateDef currentStateDef = machineDef.getStates().get(state.getCurrentState());
        if (currentStateDef == null) {
            log.warn("[StateMachine] State definition {} not found in machine def for contact={}. Completing flow.",
                    state.getCurrentState(), contact.getWaId());
            stateRepository.delete(state);
            completeFlow(state, contact, owner, state.getFlowType());
            return;
        }

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
            boolean isInteractiveSelection = (selectionId != null && !selectionId.isEmpty());
            // Use human-readable input instead of internal option ID for interactive selections
            saveAnswer(state, field, (isInteractiveSelection && input != null && !input.isBlank()) ? input : activeInput);
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
            // Send exact configured question without modifying it
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
    
    /**
     * Creates a modified state definition that shows pre-filled value to the user
     * and asks for confirmation or update.
     */
    private StateDef createPreFilledState(StateDef originalState, String existingValue) {
        String originalQuestion = originalState.getText();
        String modifiedQuestion = String.format(
            "%s\n\n💡 We have: *%s*\n\nPlease confirm by typing it again, or provide a new value:",
            originalQuestion,
            existingValue
        );
        
        return StateDef.builder()
                .type(originalState.getType())
                .text(modifiedQuestion)
                .saveInputAs(originalState.getSaveInputAs())
                .options(originalState.getOptions())
                .dynamicOptions(originalState.isDynamicOptions())
                .transitions(originalState.getTransitions())
                .fallbackState(originalState.getFallbackState())
                .build();
    }
    
    /**
     * Retrieves existing field value from Contact entity if available.
     * Supports: name, email, phone
     */
    private String getExistingFieldValue(Contact contact, String fieldKey) {
        if (contact == null || fieldKey == null) {
            return null;
        }
        
        switch (fieldKey.toLowerCase()) {
            case "name":
                if (contact.getName() != null && !contact.getName().startsWith("WhatsApp User")) {
                    return contact.getName();
                }
                break;
            case "email":
                if (contact.getEmail() != null && !contact.getEmail().isBlank()) {
                    return contact.getEmail();
                }
                break;
            case "phone":
                if (contact.getPhone() != null && !contact.getPhone().isBlank()) {
                    return contact.getPhone();
                }
                break;
        }
        
        return null;
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

        String messageToSend = response.getConfirmationMessage();
        if (!response.isSuccess()) {
            log.error("[StateMachine] FlowHandler failed for flowType={}: {}", flowType, response.getErrorReason());
            if (messageToSend == null || messageToSend.isBlank()) {
                messageToSend = "✅ Thank you! We have received your details and will be in touch shortly to confirm your appointment.";
            }
        }

        if (config != null && messageToSend != null && !messageToSend.isBlank()) {
            try {
                if (config.getFlowCompletionMenuJson() != null && !config.getFlowCompletionMenuJson().isBlank()) {
                    try {
                        com.chatcrmlite.backend.dto.MenuDto menu = objectMapper.readValue(
                                config.getFlowCompletionMenuJson(), com.chatcrmlite.backend.dto.MenuDto.class);
                        outboundService.sendInteractiveMenu(contact, menu, messageToSend, config, owner);
                    } catch (Exception e) {
                        log.warn("[StateMachine] Failed to parse configured completion menu, falling back to text", e);
                        outboundService.sendText(contact, messageToSend, config, owner);
                    }
                } else {
                    outboundService.sendText(contact, messageToSend, config, owner);
                }
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

    private String getDefaultGreetingForFlow(ConversationState.FlowType flowType) {
        if (flowType == ConversationState.FlowType.APPOINTMENT) {
            return "👋 Hello {{contact.firstName}}!\n\nWelcome! Let's schedule your appointment. Please answer a few quick questions.";
        } else if (flowType == ConversationState.FlowType.BOOKING) {
            return "👋 Hello {{contact.firstName}}!\n\nThank you for choosing us! Let's get your service booking details recorded.";
        } else if (flowType == ConversationState.FlowType.SUPPORT) {
            return "👋 Hello {{contact.firstName}}!\n\nWe're here to help! Please tell us what issue you are facing.";
        }
        return "👋 Hello {{contact.firstName}}!\n\nThank you for reaching out to us. Please share a few details so we can assist you.";
    }
}
