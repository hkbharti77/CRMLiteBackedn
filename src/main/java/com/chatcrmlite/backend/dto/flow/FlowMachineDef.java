package com.chatcrmlite.backend.dto.flow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FlowMachineDef {
    private java.util.UUID id;
    private String initialState;
    private Map<String, StateDef> states;

    public String getInitialState() { return initialState; }
    public Map<String, StateDef> getStates() { return states; }
    public void setInitialState(String initialState) { this.initialState = initialState; }
    public void setStates(Map<String, StateDef> states) { this.states = states; }
}
