package com.chatcrmlite.backend.services.whatsapp.flows;

import com.chatcrmlite.backend.models.flows.FlowOutboxEvent;
import com.chatcrmlite.backend.models.flows.FlowSubmission;
import com.chatcrmlite.backend.models.flows.OutboxStatus;
import com.chatcrmlite.backend.repositories.flows.FlowOutboxEventRepository;
import com.chatcrmlite.backend.repositories.flows.FlowSubmissionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
public class OutboxEventWorker {

    private final FlowOutboxEventRepository flowOutboxEventRepository;
    private final FlowSubmissionRepository flowSubmissionRepository;
    private final FlowSubmissionProcessor flowSubmissionProcessor;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    public OutboxEventWorker(FlowOutboxEventRepository flowOutboxEventRepository,
                             FlowSubmissionRepository flowSubmissionRepository,
                             FlowSubmissionProcessor flowSubmissionProcessor,
                             org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.flowOutboxEventRepository = flowOutboxEventRepository;
        this.flowSubmissionRepository = flowSubmissionRepository;
        this.flowSubmissionProcessor = flowSubmissionProcessor;
        this.transactionTemplate = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    }

    /**
     * Polls pending Transactional Outbox events every 3 seconds.
     */
    @Scheduled(fixedDelay = 3000)
    public void processOutboxEvents() {
        List<FlowOutboxEvent> pendingEvents = flowOutboxEventRepository.findPendingEvents(OutboxStatus.PENDING, 5);
        if (pendingEvents.isEmpty()) return;

        for (FlowOutboxEvent event : pendingEvents) {
            processSingleOutboxEvent(event);
        }
    }

    public void processSingleOutboxEvent(FlowOutboxEvent event) {
        transactionTemplate.executeWithoutResult(status -> {
            event.setRetryCount(event.getRetryCount() + 1);

            try {
                if ("FLOW_SUBMISSION".equals(event.getAggregateType())) {
                    FlowSubmission submission = flowSubmissionRepository.findById(event.getAggregateId()).orElse(null);
                    if (submission != null) {
                        flowSubmissionProcessor.processSubmission(submission);
                    } else {
                        log.warn("⚠️ [OutboxWorker] FlowSubmission {} not found for FlowOutboxEvent {}", event.getAggregateId(), event.getId());
                    }
                }

                event.setStatus(OutboxStatus.PUBLISHED);
                event.setProcessedAt(LocalDateTime.now());
                event.setLastError(null);
                flowOutboxEventRepository.save(event);
                log.info("✅ [OutboxWorker] FlowOutboxEvent {} processed successfully", event.getId());

            } catch (Exception ex) {
                log.error("❌ [OutboxWorker] Failed to process FlowOutboxEvent {}: {}", event.getId(), ex.getMessage(), ex);
                event.setLastError(ex.getMessage());
                if (event.getRetryCount() >= 5) {
                    event.setStatus(OutboxStatus.FAILED);
                }
                flowOutboxEventRepository.save(event);
            }
        });
    }
}
