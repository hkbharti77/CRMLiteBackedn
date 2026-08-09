package com.chatcrmlite.backend.services.livechat;

import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.livechat.LiveChatAuditLog;
import com.chatcrmlite.backend.models.livechat.LiveChatQueue;
import com.chatcrmlite.backend.models.livechat.LiveChatSlaEvent;
import com.chatcrmlite.backend.models.livechat.OutboxEvent;
import com.chatcrmlite.backend.repositories.LiveChatQueueRepository;
import com.chatcrmlite.backend.repositories.LiveChatSlaEventRepository;
import com.chatcrmlite.backend.repositories.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class LiveChatSlaScheduler {
    private static final Logger log = LoggerFactory.getLogger(LiveChatSlaScheduler.class);

    @Autowired private LiveChatQueueRepository queueRepository;
    @Autowired private LiveChatSlaEventRepository slaEventRepository;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private LiveChatAuditService auditService;
    @Autowired private ObjectMapper objectMapper;

    @Scheduled(fixedDelay = 60000)
    @Transactional
    public void scanAndEscalateSlaBreaches() {
        LocalDateTime now = LocalDateTime.now();
        List<LiveChatQueue> expiredQueues = queueRepository.findExpiredUnbreachedQueues(now);

        for (LiveChatQueue queue : expiredQueues) {
            try {
                // Idempotency check with DB unique constraint
                String escalationType = "CRITICAL_QUEUE_SLA_BREACH";
                if (slaEventRepository.findByQueueIdAndEscalationType(queue.getId(), escalationType).isPresent()) {
                    continue;
                }

                // Save SLA event (will throw ConstraintViolationException if duplicate)
                LiveChatSlaEvent slaEvent = new LiveChatSlaEvent(queue.getId(), queue.getTenant().getId(), escalationType);
                slaEventRepository.save(slaEvent);

                // Mark queue as breached
                queue.setSlaBreached(true);
                queueRepository.save(queue);

                Tenant tenant = queue.getTenant();
                auditService.recordAudit(tenant.getId(), queue.getContact().getId(), null, LiveChatAuditLog.AuditAction.SLA_BREACHED, null, null, "SLA-SCHEDULER", "Queue SLA breached after " + queue.getSlaExpiresAt());

                // Create Outbox Event for SLA Breach Emails to Admins/Owner
                Map<String, Object> payload = Map.of(
                        "queueId", queue.getId().toString(),
                        "contactId", queue.getContact().getId().toString(),
                        "contactName", queue.getContact().getName() != null ? queue.getContact().getName() : "Customer",
                        "contactPhone", queue.getContact().getWaId(),
                        "slaMinutes", 30
                );

                OutboxEvent outboxEvent = new OutboxEvent(tenant.getId(), "SLA_BREACHED", queue.getContact().getId().toString(), objectMapper.writeValueAsString(payload));
                outboxEventRepository.save(outboxEvent);

                log.warn("SLA Breach escalated for queued contact {} in tenant {}", queue.getContact().getId(), tenant.getId());

            } catch (Exception e) {
                log.error("Failed processing SLA breach for queue {}", queue.getId(), e);
            }
        }
    }
}
