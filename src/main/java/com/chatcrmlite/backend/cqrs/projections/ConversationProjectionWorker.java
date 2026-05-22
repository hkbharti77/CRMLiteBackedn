package com.chatcrmlite.backend.cqrs.projections;

import com.chatcrmlite.backend.events.conversation.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ConversationProjectionWorker {

    private final ConversationSummaryRepository repository;

    @Transactional
    public void project(ConversationEvent event) {
        if (event instanceof FlowStartedEvent e) {
            handleFlowStarted(e);
        } else if (event instanceof StepAdvancedEvent e) {
            handleStepAdvanced(e);
        }
    }

    private void handleFlowStarted(FlowStartedEvent e) {
        ConversationSummary summary = ConversationSummary.builder()
                .conversationId(e.getConversationId())
                .flowType(e.getFlowType())
                .currentState("START")
                .lastUpdatedAt(e.getTimestamp())
                .build();
        repository.save(summary);
    }

    private void handleStepAdvanced(StepAdvancedEvent e) {
        repository.findById(e.getConversationId()).ifPresent(summary -> {
            summary.setCurrentState(e.getToState());
            summary.setLastUpdatedAt(e.getTimestamp());
            repository.save(summary);
        });
    }
}
