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
public class TransitionDef {
    
    // The target state to transition to if guards pass
    private String target;
    
    // List of guard conditions that must ALL evaluate to true for this transition to be valid
    // e.g. "VALIDATE_EMAIL", "SERVICE_EXISTS", or simple SpEL if needed later.
    private List<String> guards;
    
    // List of actions to perform during this transition
    // e.g. "COMPLETE_APPOINTMENT", "NOTIFY_OWNER"
    private List<String> actions;

    public String getTarget() { return target; }
    public List<String> getGuards() { return guards; }
    public List<String> getActions() { return actions; }
    public void setTarget(String target) { this.target = target; }
    public void setGuards(List<String> guards) { this.guards = guards; }
    public void setActions(List<String> actions) { this.actions = actions; }
}
