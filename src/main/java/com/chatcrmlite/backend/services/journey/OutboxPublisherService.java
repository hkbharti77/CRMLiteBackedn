package com.chatcrmlite.backend.services.journey;

import com.chatcrmlite.backend.models.journey.JourneyOutboxEvent;
import com.chatcrmlite.backend.repositories.journey.JourneyOutboxEventRepository;
import com.chatcrmlite.backend.utils.CorrelationContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class OutboxPublisherService {

    private final JourneyOutboxEventRepository outboxEventRepository;
    private final ApplicationEventPublisher applicationEventPublisher;

    /**
     * Publishes a business domain event into the Transactional Outbox.
     * Must be called within the same DB transaction as the business operation.
     */
    @Transactional
    public JourneyOutboxEvent publishEvent(UUID eventId, String businessId, String aggregateType, String aggregateId, String eventType, String payload) {
        String correlationId = CorrelationContext.getCorrelationId();

        JourneyOutboxEvent event = JourneyOutboxEvent.builder()
                .eventId(eventId != null ? eventId : UUID.randomUUID())
                .businessId(businessId)
                .correlationId(correlationId)
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .eventType(eventType)
                .payload(payload)
                .status("PENDING")
                .nextAttemptAt(ZonedDateTime.now())
                .retryCount(0)
                .build();

        JourneyOutboxEvent saved = outboxEventRepository.save(event);
        log.info("[Outbox] Saved outbox event {} type={} aggId={} corrId={}", saved.getEventId(), eventType, aggregateId, correlationId);
        return saved;
    }

    /**
     * Scheduled worker task to process pending outbox events using SKIP LOCKED.
     */
    @Scheduled(fixedDelay = 2000)
    public void processOutboxEvents() {
        ZonedDateTime now = ZonedDateTime.now();
        List<JourneyOutboxEvent> claimableEvents = outboxEventRepository.claimPendingEvents(now, 50);

        if (claimableEvents.isEmpty()) {
            return;
        }

        String workerId = "worker-" + Thread.currentThread().getName() + "-" + System.currentTimeMillis();
        ZonedDateTime leaseUntil = now.plusMinutes(2);

        for (JourneyOutboxEvent event : claimableEvents) {
            try {
                CorrelationContext.setCorrelationId(event.getCorrelationId());
                event.setStatus("CLAIMED");
                event.setClaimedAt(now);
                event.setClaimedBy(workerId);
                event.setLockLeaseUntil(leaseUntil);
                outboxEventRepository.save(event);

                // Publish to Spring Event System / Queue
                log.info("[OutboxWorker] Dispatching event {} ({}) for biz={}", event.getEventId(), event.getEventType(), event.getBusinessId());
                applicationEventPublisher.publishEvent(event);

                event.setStatus("PUBLISHED");
                event.setProcessedAt(ZonedDateTime.now());
                outboxEventRepository.save(event);
            } catch (Exception e) {
                log.error("[OutboxWorker] Failed to dispatch event {}: {}", event.getEventId(), e.getMessage(), e);
                event.setStatus("FAILED");
                event.setRetryCount(event.getRetryCount() + 1);
                event.setNextAttemptAt(ZonedDateTime.now().plusSeconds((long) Math.pow(2, Math.min(event.getRetryCount(), 6))));
                event.setLastError(e.getMessage());
                outboxEventRepository.save(event);
            } finally {
                CorrelationContext.clear();
            }
        }
    }
}
