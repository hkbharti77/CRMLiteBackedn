package com.chatcrmlite.backend.services.workflow;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Orchestrates the transition between processing stages.
 */
@Service
@RequiredArgsConstructor
public class WorkflowOrchestrator {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WorkflowOrchestrator.class);

    private final QueueRouter router;
    private final WorkflowStateTracker tracker;
    private final MeterRegistry meterRegistry;
    private final com.chatcrmlite.backend.services.whatsapp.WhatsAppIngressService whatsappIngressService;
    private final com.chatcrmlite.backend.services.tenant.QuotaEnforcerService quotaEnforcerService;

    @Transactional
    public void startWorkflow(String messageId, String waId, UUID tenantId, String payload) {
        Timer.Sample sample = Timer.start(meterRegistry);
        ProcessingContext context = ProcessingContext.builder()
                .messageId(messageId)
                .waId(waId)
                .tenantId(tenantId)
                .payload(payload)
                .timestamp(System.currentTimeMillis())
                .currentStage(ProcessingContext.WorkflowStage.INGRESS)
                .build();

        tracker.trackStage(messageId, ProcessingContext.WorkflowStage.INGRESS);
        
        // STAGE 1: INGRESS (Resolve Contact, Save Message, Check Idempotency)
        whatsappIngressService.resolveAndSaveIngress(context);

        // Routing logic based on type
        String type = (String) context.getMetadata().get("type");
        boolean hasActiveFlow = Boolean.TRUE.equals(context.getMetadata().get("hasActiveFlow"));
        boolean botPaused = Boolean.TRUE.equals(context.getMetadata().get("botPaused"));

        if (botPaused) {
            log.info("⏸️ [Workflow] Bot is paused for contact {}. Skipping AI/Flow routing.", context.getWaId());
            completeStage(context, ProcessingContext.WorkflowStage.COMPLETED);
            return;
        }

        // Route to flow worker if:
        //   1. The message is an interactive selection (button/list tap), OR
        //   2. The contact is currently mid-flow (free-text reply during a flow step), OR
        //   3. The tenant's plan does not support AI (RAG LLM).
        boolean routeToFlow = "interactive".equals(type) || hasActiveFlow;
        
        if (!routeToFlow) {
            try {
                com.chatcrmlite.backend.models.TenantSubscription sub = quotaEnforcerService.getActiveSubscription(tenantId);
                if (sub != null && !sub.getPlan().isHasRagLlm()) {
                    routeToFlow = true;
                    log.info("Tenant {} plan does not support RAG LLM. Routing to menu/flow.", tenantId);
                }
            } catch (Exception e) {
                if (e instanceof IllegalStateException && e.getMessage().contains("Tenant not found")) {
                    log.warn("Aborting workflow: Tenant {} not found in database.", tenantId);
                    return;
                }
                log.warn("Failed to check subscription for RAG LLM, defaulting to AI if applicable", e);
            }
        }

        if (routeToFlow) {
            router.routeToFlow(context);
        } else {
            router.routeToAi(context);
        }
    }

    public void completeStage(ProcessingContext context, ProcessingContext.WorkflowStage nextStage) {
        context.setCurrentStage(nextStage);
        tracker.trackStage(context.getMessageId(), nextStage);

        switch (nextStage) {
            case FLOW_EXECUTION:
                router.routeToFlow(context);
                break;
            case DELIVERY:
                router.routeToDelivery(context);
                break;
            case COMPLETED:
                log.info("🏁 [Workflow] Message {} completed successfully.", context.getMessageId());
                tracker.releaseUserLock(context.getWaId());
                break;
            case FAILED:
                log.error("❌ [Workflow] Message {} failed.", context.getMessageId());
                tracker.releaseUserLock(context.getWaId());
                meterRegistry.counter("workflow.failures", "tenant", context.getTenantId().toString()).increment();
                break;
        }

        // Record total processing time up to this point
        Timer.builder("workflow.stage.latency")
                .tag("stage", nextStage.name())
                .tag("tenant", context.getTenantId().toString())
                .register(meterRegistry)
                .record(java.time.Duration.ofMillis(System.currentTimeMillis() - context.getTimestamp()));
    }
}
