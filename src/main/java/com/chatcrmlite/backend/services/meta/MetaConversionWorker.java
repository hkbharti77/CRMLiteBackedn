package com.chatcrmlite.backend.services.meta;

import com.chatcrmlite.backend.models.MetaConversionEvent;
import com.chatcrmlite.backend.repositories.MetaConversionEventRepository;
import com.chatcrmlite.backend.services.meta.dto.MetaDeliveryResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MetaConversionWorker {

    private final MetaConversionEventRepository repository;
    private final MetaConversionService metaConversionService;
    
    @Value("${tenant.metaConversionsEnabled:true}")
    private boolean isEnabled;

    @Value("${tenant.metaConversionsMode:ACTIVE}") // DISABLED, SHADOW, ACTIVE
    private String mode;

    @Scheduled(fixedDelay = 10000) // Poll every 10 seconds
    @Transactional
    public void processOutbox() {
        if (!isEnabled || "DISABLED".equalsIgnoreCase(mode)) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime leaseExpiry = now.minusMinutes(5); // Lease duration of 5 mins

        // Claim events safely using FOR UPDATE SKIP LOCKED
        List<MetaConversionEvent> claimableEvents = repository.findClaimableEvents(now, leaseExpiry);
        if (claimableEvents.isEmpty()) {
            return;
        }

        String workerId = UUID.randomUUID().toString(); // unique ID for this worker thread's lease

        for (MetaConversionEvent event : claimableEvents) {
            try {
                // Lock event
                event.setLockedAt(now);
                event.setLockedBy(workerId);
                event.setStatus(MetaConversionEvent.EventStatus.PROCESSING);
                repository.saveAndFlush(event);

                if ("SHADOW".equalsIgnoreCase(mode)) {
                    log.info("[META CAPI - SHADOW] Would have sent event: {}", event.getEventId());
                    event.setStatus(MetaConversionEvent.EventStatus.SUCCESS);
                    event.setSentAt(now);
                    event.setLockedAt(null);
                    event.setLockedBy(null);
                    repository.save(event);
                    continue;
                }

                // Send to Meta
                MetaDeliveryResult result = metaConversionService.processAndSend(event);
                event.setAttemptCount(event.getAttemptCount() + 1);
                event.setLastAttemptAt(LocalDateTime.now());
                event.setLastHttpStatus(result.getHttpStatus());
                event.setMetaTraceId(result.getFbtraceId());

                if (result.isTransportSuccess() && result.getEventsFailed() == 0) {
                    // SUCCESS
                    event.setStatus(MetaConversionEvent.EventStatus.SUCCESS);
                    event.setSentAt(LocalDateTime.now());
                    log.info("[META CAPI] ✅ SUCCESS - WABA: {} | Event: {} | Trace ID: {}", 
                        mask(event.getWabaId()), event.getEventName(), result.getFbtraceId());
                } else {
                    // FAILURE / PARTIAL FAILURE
                    event.setLastErrorCode(String.valueOf(result.getHttpStatus()));
                    event.setLastErrorMessage(result.getErrorMessage());
                    handleRetry(event);
                    log.warn("[META CAPI] ⚠️ FAILURE - Event: {} | Reason: {}", event.getEventId(), result.getErrorMessage());
                }

            } catch (Exception e) {
                log.error("[META CAPI] Unexpected error processing event: {}", event.getId(), e);
                event.setLastErrorMessage(e.getMessage());
                handleRetry(event);
            } finally {
                // Unlock
                event.setLockedAt(null);
                event.setLockedBy(null);
                repository.save(event);
            }
        }
    }

    private void handleRetry(MetaConversionEvent event) {
        int maxRetries = 5;
        if (event.getAttemptCount() >= maxRetries) {
            event.setStatus(MetaConversionEvent.EventStatus.DEAD_LETTER);
            log.error("[META CAPI] Event {} moved to DEAD_LETTER after {} attempts.", event.getEventId(), maxRetries);
        } else {
            event.setStatus(MetaConversionEvent.EventStatus.RETRY);
            // Exponential backoff: 1 min, 5 mins, 25 mins, etc.
            int minutesToWait = (int) Math.pow(5, event.getAttemptCount() - 1);
            event.setNextAttemptAt(LocalDateTime.now().plusMinutes(minutesToWait));
        }
    }

    private String mask(String input) {
        if (input == null || input.length() <= 4) return "****";
        return "****" + input.substring(input.length() - 4);
    }
}
