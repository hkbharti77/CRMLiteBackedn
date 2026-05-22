package com.chatcrmlite.backend.dto.flow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StateDef {
    
    public enum StateType {
        MESSAGE,    // Sends a message and waits for input
        EVALUATE,   // Internal evaluation state (no waiting, instantly transitions)
        END         // Terminal state, completes the flow
    }

    @Builder.Default
    private StateType type = StateType.MESSAGE;
    
    private String text; // The message to send to the user
    
    // For interactive buttons or list options
    private List<String> options;
    
    // Whether this state fetches options dynamically from the DB (e.g. Services)
    private boolean dynamicOptions;

    public String getText() { return text; }
    public List<String> getOptions() { return options; }
    public boolean isDynamicOptions() { return dynamicOptions; }
    public void setText(String text) { this.text = text; }
    public void setOptions(List<String> options) { this.options = options; }
    public void setDynamicOptions(boolean dynamicOptions) { this.dynamicOptions = dynamicOptions; }

    // Ordered list of transitions out of this state
    private List<TransitionDef> transitions;
    
    // If no transitions match, fallback to this state
    private String fallbackState;

    // The data key to save the user's input under (if it's a MESSAGE state)
    private String saveInputAs;

    public StateType getType() { return type; }
    public String getSaveInputAs() { return saveInputAs; }
    public void setType(StateType type) { this.type = type; }
    public void setSaveInputAs(String saveInputAs) { this.saveInputAs = saveInputAs; }

    public List<TransitionDef> getTransitions() { return transitions; }
    public String getFallbackState() { return fallbackState; }
    public void setTransitions(List<TransitionDef> transitions) { this.transitions = transitions; }
    public void setFallbackState(String fallbackState) { this.fallbackState = fallbackState; }
}
