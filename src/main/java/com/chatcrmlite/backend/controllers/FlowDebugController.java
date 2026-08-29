package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.dto.flow.FlowMachineDef;
import com.chatcrmlite.backend.dto.flow.StateDef;
import com.chatcrmlite.backend.dto.flow.TransitionDef;
import com.chatcrmlite.backend.models.ConversationState;
import com.chatcrmlite.backend.repositories.ConversationStateRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.services.flow.FlowDefinitionLoader;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/flow-debug")
@RequiredArgsConstructor
public class FlowDebugController {

    private final FlowDefinitionLoader definitionLoader;
    private final ConversationStateRepository stateRepository;
    private final UserRepository userRepository;

    private User getAuthenticatedUser() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        String email;
        if (principal instanceof UserDetails) {
            email = ((UserDetails) principal).getUsername();
        } else {
            email = principal.toString();
        }
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    /**
     * Generates a Mermaid.js diagram for a given Flow Definition ID.
     */
    @GetMapping("/{definitionId}/mermaid")
    public ResponseEntity<String> getFlowMermaidDiagram(@PathVariable UUID definitionId) {
        FlowMachineDef def = definitionLoader.loadDefinition(definitionId);
        
        StringBuilder sb = new StringBuilder();
        sb.append("stateDiagram-v2\n");
        sb.append("    [*] --> ").append(def.getInitialState()).append("\n");

        for (Map.Entry<String, StateDef> entry : def.getStates().entrySet()) {
            String stateName = entry.getKey();
            StateDef state = entry.getValue();

            // Note: state descriptions could be added as Mermaid notes

            if (state.getType() == StateDef.StateType.END) {
                sb.append("    ").append(stateName).append(" --> [*]\n");
            }

            if (state.getTransitions() != null) {
                for (TransitionDef t : state.getTransitions()) {
                    sb.append("    ").append(stateName).append(" --> ").append(t.getTarget());
                    
                    if (t.getGuards() != null && !t.getGuards().isEmpty()) {
                        sb.append(" : [").append(String.join(", ", t.getGuards())).append("]");
                    }
                    sb.append("\n");
                }
            }

            if (state.getFallbackState() != null) {
                sb.append("    ").append(stateName).append(" --> ").append(state.getFallbackState())
                  .append(" : [FALLBACK]\n");
            }
        }

        return ResponseEntity.ok()
                .header("Content-Type", "text/plain")
                .body(sb.toString());
    }

    /**
     * View the real-time state history of a given contact's conversation flow.
     */
    @GetMapping("/contact/{contactId}/history")
    public ResponseEntity<String> getContactFlowHistory(@PathVariable UUID contactId) {
        User user = getAuthenticatedUser();
        ConversationState state = stateRepository.findByIdAndTenantId(contactId, user.getTenant().getId()).orElse(null);
        if (state == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .header("Content-Type", "application/json")
                .body(state.getStateHistory());
    }
}
