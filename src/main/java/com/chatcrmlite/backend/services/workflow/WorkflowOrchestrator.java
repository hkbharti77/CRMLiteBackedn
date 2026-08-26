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
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowOrchestrator {

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

        // 1. Acquire Per-User Lock to ensure sequential processing for this user
        boolean lockAcquired = tracker.acquireUserLock(waId);
        if (!lockAcquired) {
            log.warn("⚠️ [Workflow] Could not acquire per-user lock for waId={} messageId={}. Proceeding to prevent message drop.", waId, messageId);
        }

        tracker.trackStage(messageId, ProcessingContext.WorkflowStage.INGRESS);
        
        try {
            // STAGE 1: INGRESS (Check Idempotency, Resolve Contact, Save Message)
            whatsappIngressService.resolveAndSaveIngress(context);

            if (Boolean.TRUE.equals(context.getMetadata().get("isDuplicate"))) {
                log.info("🛑 [Workflow] Duplicate message detected for messageId={}. Halting workflow.", messageId);
                completeStage(context, ProcessingContext.WorkflowStage.COMPLETED);
                return;
            }

            // Routing logic based on type
            String type = (String) context.getMetadata().get("type");
            String text = (String) context.getMetadata().get("text");
            boolean isEcho = Boolean.TRUE.equals(context.getMetadata().get("isEcho"));
            boolean isFlowNfmReply = Boolean.TRUE.equals(context.getMetadata().get("isFlowNfmReply"));
            boolean hasActiveFlow = Boolean.TRUE.equals(context.getMetadata().get("hasActiveFlow"));
            boolean botPaused = Boolean.TRUE.equals(context.getMetadata().get("botPaused"));

            if (isEcho || botPaused || isFlowNfmReply) {
                log.info("⏸️ [Workflow] SMB Echo, Bot Paused, or Flow nfm_reply for contact {}. Skipping AI/Flow routing.", context.getWaId());
                completeStage(context, ProcessingContext.WorkflowStage.COMPLETED);
                return;
            }

            // Check if message is a flow trigger, navigation command, or form cancel
            String lower = (text != null ? text.trim().toLowerCase() : "");
            String cleanLower = lower.replaceAll("[^a-z0-9 ]", "").trim();
            boolean isCommandOrIntent = cleanLower.matches("^(cancel|stop|exit|quit|terminate|menu|options|help|start|services|show|hi|hello|hey|namaste|hii|heyy|hi there|hello there|good morning|good evening|good afternoon)$")
                    || cleanLower.matches(".*(appointment|doctor|clinic|consultation|checkup|specialist|dentist|physician|salon|spa|booking|reserve|slot|table|haircut|facial|massage|reservation|quote|pricing|inquiry|inquire|lead|enquiry|estimate|catalog|feedback|rating|review|survey|complaint|support).*");

            // Route to flow worker if:
            //   1. An active form/flow is currently running for this contact (PRIORITY: isolates active form from RAG/AI), OR
            //   2. The message is an interactive selection (button/list tap), OR
            //   3. The message is a command or trigger intent (cancel, menu, appointment, booking, lead, etc.), OR
            //   4. The tenant's plan does not support AI (RAG LLM).
            boolean routeToFlow = hasActiveFlow || "interactive".equals(type) || isCommandOrIntent;
            
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
                        completeStage(context, ProcessingContext.WorkflowStage.FAILED);
                        return;
                    }
                    log.warn("Failed to check subscription for RAG LLM, defaulting to AI if applicable", e);
                }
            }

            if (routeToFlow) {
                if (hasActiveFlow) {
                    log.info("🔒 [Workflow] Active form in progress for contact {}. Directing exclusively to FlowWorker (AI/RAG suppressed).", context.getWaId());
                }
                router.routeToFlow(context);
            } else {
                router.routeToAi(context);
            }
        } catch (Exception e) {
            log.error("❌ [Workflow] Unhandled error during startWorkflow for messageId={}", messageId, e);
            completeStage(context, ProcessingContext.WorkflowStage.FAILED);
            throw e;
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
