package com.chatcrmlite.backend.services.flow;

import com.chatcrmlite.backend.dto.flow.StateDef;
import com.chatcrmlite.backend.dto.flow.TransitionDef;
import com.chatcrmlite.backend.flow.FlowContext;
import com.chatcrmlite.backend.models.BusinessService;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.BusinessServiceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class TransitionEngineTest {

    @Mock
    private BusinessServiceRepository businessServiceRepository;

    @InjectMocks
    private TransitionEngine transitionEngine;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testEvaluateNextState_withValidEmailGuard() {
        StateDef currentState = new StateDef();
        currentState.setFallbackState("ASK_EMAIL_AGAIN");

        TransitionDef transition = new TransitionDef();
        transition.setTarget("NEXT_STATE");
        transition.setGuards(List.of("VALIDATE_EMAIL"));
        
        currentState.setTransitions(List.of(transition));

        FlowContext context = FlowContext.builder().build();

        // Valid email
        String nextState = transitionEngine.evaluateNextState(currentState, "test@example.com", context);
        assertEquals("NEXT_STATE", nextState);

        // Invalid email
        String fallbackState = transitionEngine.evaluateNextState(currentState, "invalid-email", context);
        assertEquals("ASK_EMAIL_AGAIN", fallbackState);
    }

    @Test
    void testEvaluateNextState_withServiceExistsGuard() {
        User owner = new User();
        BusinessService service = new BusinessService();
        service.setName("Haircut");
        
        when(businessServiceRepository.findByOwner(owner)).thenReturn(List.of(service));

        StateDef currentState = new StateDef();
        currentState.setFallbackState("ASK_SERVICE_AGAIN");

        TransitionDef transition = new TransitionDef();
        transition.setTarget("CONFIRM_SERVICE");
        transition.setGuards(List.of("SERVICE_EXISTS"));
        
        currentState.setTransitions(List.of(transition));

        FlowContext context = FlowContext.builder().owner(owner).build();

        // Valid service
        String nextState = transitionEngine.evaluateNextState(currentState, "Haircut", context);
        assertEquals("CONFIRM_SERVICE", nextState);

        // Invalid service
        String fallbackState = transitionEngine.evaluateNextState(currentState, "Coloring", context);
        assertEquals("ASK_SERVICE_AGAIN", fallbackState);
    }
}
