package com.chatcrmlite.backend.services.flow;

import com.chatcrmlite.backend.dto.flow.StateDef;
import com.chatcrmlite.backend.dto.flow.TransitionDef;
import com.chatcrmlite.backend.flow.FlowContext;
import com.chatcrmlite.backend.models.BusinessService;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.BusinessServiceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TransitionEngine {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TransitionEngine.class);

    private final BusinessServiceRepository businessServiceRepository;

    /**
     * Evaluates the transitions for a given state and input to determine the next state.
     * Returns the name of the target state.
     */
    public String evaluateNextState(StateDef currentState, String input, FlowContext context) {
        if (currentState.getTransitions() == null || currentState.getTransitions().isEmpty()) {
            return currentState.getFallbackState() != null ? currentState.getFallbackState() : null;
        }

        for (TransitionDef transition : currentState.getTransitions()) {
            if (evaluateGuards(transition.getGuards(), input, context)) {
                executeActions(transition.getActions(), input, context);
                return transition.getTarget();
            }
        }

        return currentState.getFallbackState();
    }

    /**
     * Returns true if ALL guards pass. If there are no guards, it passes by default.
     */
    private boolean evaluateGuards(List<String> guards, String input, FlowContext context) {
        if (guards == null || guards.isEmpty()) return true;

        for (String guard : guards) {
            boolean result = switch (guard) {
                case "VALIDATE_EMAIL" -> input != null && input.matches("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,6}$");
                case "SERVICE_EXISTS" -> checkServiceExists(input, context.getOwner());
                case "ALWAYS_TRUE" -> true;
                default -> {
                    log.warn("[TransitionEngine] Unknown guard '{}', treating as false", guard);
                    yield false;
                }
            };

            if (!result) return false;
        }
        return true;
    }

    /**
     * Executes defined side-effects for a transition.
     */
    private void executeActions(List<String> actions, String input, FlowContext context) {
        if (actions == null || actions.isEmpty()) return;

        for (String action : actions) {
            switch (action) {
                // In the future, this is where we would trigger events, but for now we'll 
                // handle final completion via FlowHandlers to keep backward compatibility.
                case "LOG_TRANSITION" -> log.info("[TransitionEngine] Transition action executed for {}", context.getContact().getWaId());
                default -> log.debug("[TransitionEngine] Unknown action '{}'", action);
            }
        }
    }

    private boolean checkServiceExists(String input, User owner) {
        List<BusinessService> services = businessServiceRepository.findByOwner(owner);
        return services.stream().anyMatch(s -> s.getName().equalsIgnoreCase(input));
    }
}
