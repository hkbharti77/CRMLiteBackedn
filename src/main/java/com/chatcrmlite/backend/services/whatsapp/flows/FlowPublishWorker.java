package com.chatcrmlite.backend.services.whatsapp.flows;

import com.chatcrmlite.backend.clients.MetaFlowClient;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.models.flows.*;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import com.chatcrmlite.backend.repositories.flows.FlowPublishJobRepository;
import com.chatcrmlite.backend.repositories.flows.FlowRevisionRepository;
import com.chatcrmlite.backend.repositories.flows.WhatsAppFlowRepository;
import com.chatcrmlite.backend.services.websocket.DistributedWebSocketPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class FlowPublishWorker {

    private final FlowPublishJobRepository jobRepository;
    private final WhatsAppFlowRepository flowRepository;
    private final FlowRevisionRepository revisionRepository;
    private final WhatsAppConfigRepository whatsappConfigRepository;
    private final MetaFlowClient metaFlowClient;
    private final FlowSchemaBuilder schemaBuilder;
    private final WhatsAppFlowAuditService auditService;
    private final DistributedWebSocketPublisher webSocketPublisher;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    public FlowPublishWorker(FlowPublishJobRepository jobRepository,
                             WhatsAppFlowRepository flowRepository,
                             FlowRevisionRepository revisionRepository,
                             WhatsAppConfigRepository whatsappConfigRepository,
                             MetaFlowClient metaFlowClient,
                             FlowSchemaBuilder schemaBuilder,
                             WhatsAppFlowAuditService auditService,
                             DistributedWebSocketPublisher webSocketPublisher,
                             org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.jobRepository = jobRepository;
        this.flowRepository = flowRepository;
        this.revisionRepository = revisionRepository;
        this.whatsappConfigRepository = whatsappConfigRepository;
        this.metaFlowClient = metaFlowClient;
        this.schemaBuilder = schemaBuilder;
        this.auditService = auditService;
        this.webSocketPublisher = webSocketPublisher;
        this.transactionTemplate = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    }

    /**
     * Polls and processes pending publish jobs every 5 seconds.
     */
    @Scheduled(fixedDelay = 5000)
    public void processPendingPublishJobs() {
        List<FlowPublishJob> dueJobs = jobRepository.findDueJobs(LocalDateTime.now());
        if (dueJobs.isEmpty()) return;

        log.info("⚙️ [FlowPublishWorker] Found {} due publish jobs to process", dueJobs.size());
        for (FlowPublishJob job : dueJobs) {
            try {
                processSingleJob(job.getId());
            } catch (Exception ex) {
                log.error("❌ [FlowPublishWorker] Unhandled error processing job {}: {}", job.getId(), ex.getMessage(), ex);
            }
        }
    }

    public void processSingleJob(UUID jobId) {
        PublishJobContext ctx = transactionTemplate.execute(status -> {
            FlowPublishJob j = jobRepository.findById(jobId).orElse(null);
            if (j == null || (j.getStatus() != PublishJobStatus.PENDING && j.getStatus() != PublishJobStatus.PROCESSING)) return null;

            WhatsAppFlow flow = flowRepository.findByIdForUpdate(j.getFlow().getId()).orElse(null);
            FlowRevision revision = j.getRevision();
            if (flow == null || revision == null) return null;

            // Enforce concurrency lease: only one active PUBLISHING revision per flow
            boolean anotherPublishing = revisionRepository.existsByFlowIdAndRevisionStatus(flow.getId(), com.chatcrmlite.backend.models.flows.FlowRevisionStatus.PUBLISHING);
            if (anotherPublishing && revision.getRevisionStatus() != com.chatcrmlite.backend.models.flows.FlowRevisionStatus.PUBLISHING) {
                // Backoff and retry later since another revision holds the lease
                j.setNextRetryAt(LocalDateTime.now().plusSeconds(30));
                jobRepository.save(j);
                return null;
            }

            revision.setRevisionStatus(com.chatcrmlite.backend.models.flows.FlowRevisionStatus.PUBLISHING);
            revisionRepository.save(revision);

            j.setStatus(PublishJobStatus.PROCESSING);
            j.setAttempts(j.getAttempts() + 1);
            jobRepository.save(j);

            PublishJobContext c = new PublishJobContext();
            c.jobId = j.getId();
            c.flowId = flow.getId();
            c.revisionId = revision.getId();
            c.flowName = flow.getName();
            c.categoryName = flow.getCategory().name();
            c.activeMetaFlowId = flow.getMetaFlowId();
            c.revisionMetaFlowId = revision.getMetaFlowId();
            c.fieldsConfigJson = revision.getFieldsConfigJson();
            c.flowJson = revision.getFlowJson();
            c.attempts = j.getAttempts();
            c.maxAttempts = j.getMaxAttempts();
            c.createdBy = revision.getCreatedBy();

            UUID tenantId = (flow.getTenant() != null) ? flow.getTenant().getId() : (j.getTenant() != null ? j.getTenant().getId() : null);
            c.tenantId = tenantId;

            if (tenantId != null) {
                whatsappConfigRepository.findByTenantId(tenantId).ifPresent(cfg -> {
                    c.accessToken = cfg.getAccessToken();
                    c.wabaId = (flow.getWabaId() != null && !flow.getWabaId().isBlank()) ? flow.getWabaId() : cfg.getWabaId();
                });
            }
            if (c.wabaId == null || c.wabaId.isBlank()) {
                c.wabaId = flow.getWabaId();
            }

            return c;
        });

        if (ctx == null) return;

        try {
            if (ctx.tenantId == null) {
                throw new IllegalStateException("Tenant is missing on flow " + ctx.flowId);
            }
            if (ctx.accessToken == null || ctx.accessToken.isBlank()) {
                throw new IllegalStateException("WhatsApp access token is missing or not configured for tenant " + ctx.tenantId);
            }
            if (ctx.wabaId == null || ctx.wabaId.isBlank()) {
                throw new IllegalStateException("WABA ID is not configured on tenant WhatsApp account");
            }

            // Step 1: Create Meta Flow Container if it doesn't exist
            String metaFlowId = ctx.revisionMetaFlowId;
            if (metaFlowId == null || metaFlowId.isBlank()) {
                metaFlowId = metaFlowClient.createFlowContainer(ctx.wabaId, ctx.flowName, List.of(ctx.categoryName), ctx.accessToken, ctx.activeMetaFlowId);
                final String finalMetaFlowId = metaFlowId;
                transactionTemplate.executeWithoutResult(status -> {
                    FlowRevision r = revisionRepository.findById(ctx.revisionId).orElse(null);
                    if (r != null) {
                        r.setMetaFlowId(finalMetaFlowId);
                        revisionRepository.save(r);
                    }
                });
                ctx.revisionMetaFlowId = metaFlowId;
            }

            // Step 2: Compile & Upload Flow JSON Assets (Version 7.0)
            String flowJson = null;
            if (ctx.fieldsConfigJson != null && !ctx.fieldsConfigJson.isBlank()) {
                flowJson = schemaBuilder.buildMetaFlowJson(ctx.flowName, "Please complete the form below:", ctx.fieldsConfigJson);
                final String finalFlowJson = flowJson;
                transactionTemplate.executeWithoutResult(status -> {
                    FlowRevision r = revisionRepository.findById(ctx.revisionId).orElse(null);
                    if (r != null) {
                        r.setFlowJson(finalFlowJson);
                        revisionRepository.save(r);
                    }
                });
            } else {
                flowJson = ctx.flowJson;
                if (flowJson != null) {
                    flowJson = flowJson.replaceAll("\"version\"\\s*:\\s*\"[^\"]+\"", "\"version\": \"7.0\"");
                    final String finalFlowJson = flowJson;
                    transactionTemplate.executeWithoutResult(status -> {
                        FlowRevision r = revisionRepository.findById(ctx.revisionId).orElse(null);
                        if (r != null) {
                            r.setFlowJson(finalFlowJson);
                            revisionRepository.save(r);
                        }
                    });
                }
            }
            metaFlowClient.uploadFlowAssets(metaFlowId, flowJson, ctx.accessToken);

            // Step 3: Publish Flow on Meta
            metaFlowClient.publishFlow(metaFlowId, ctx.accessToken);

            com.chatcrmlite.backend.clients.MetaFlowClient.FlowStatusResult metaStatus = metaFlowClient.fetchFlowStatus(metaFlowId, ctx.accessToken);
            if (!"PUBLISHED".equalsIgnoreCase(metaStatus.getStatus())) {
                throw new IllegalStateException("Meta API returned status '" + metaStatus.getStatus() + "' after publish. Reconciliation required.");
            }

            // Step 4: Atomic Database State Updates
            final String finalMetaFlowId = metaFlowId;
            transactionTemplate.executeWithoutResult(status -> {
                WhatsAppFlow f = flowRepository.findByIdForUpdate(ctx.flowId).orElse(null);
                if (f == null) return;

                List<FlowRevision> existingRevisions = revisionRepository.findAllByFlowIdOrderByVersionDesc(ctx.flowId);
                for (FlowRevision r : existingRevisions) {
                    if (r.getRevisionStatus() == com.chatcrmlite.backend.models.flows.FlowRevisionStatus.PUBLISHED && !r.getId().equals(ctx.revisionId)) {
                        r.setRevisionStatus(com.chatcrmlite.backend.models.flows.FlowRevisionStatus.SUPERSEDED);
                        revisionRepository.save(r);
                    }
                }

                FlowRevision rev = revisionRepository.findById(ctx.revisionId).orElse(null);
                if (rev != null) {
                    rev.setRevisionStatus(com.chatcrmlite.backend.models.flows.FlowRevisionStatus.PUBLISHED);
                    rev.setMetaStatus(metaStatus.getStatus());
                    try {
                        rev.setValidationErrorsJson(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(metaStatus.getValidationErrors()));
                    } catch (Exception ignored) {}
                    rev.setPublishedAt(LocalDateTime.now());
                    revisionRepository.save(rev);
                    f.setPublishedRevision(rev);
                }

                f.setActiveRevisionId(ctx.revisionId);
                f.setActiveMetaFlowId(finalMetaFlowId);
                f.setStatus(FlowLifecycleStatus.PUBLISHED);
                f.setPublishedAt(LocalDateTime.now());
                f.setLastSyncError(null);
                flowRepository.save(f);

                FlowPublishJob j = jobRepository.findById(jobId).orElse(null);
                if (j != null) {
                    j.setStatus(PublishJobStatus.COMPLETED);
                    j.setCompletedAt(LocalDateTime.now());
                    jobRepository.save(j);
                }
            });

            auditService.logAction(ctx.flowId, ctx.revisionId, ctx.createdBy,
                    FlowAuditAction.FLOW_PUBLISHED, "PUBLISHING", "PUBLISHED", "{\"metaFlowId\":\"" + finalMetaFlowId + "\"}");

            broadcastFlowUpdate(ctx.tenantId, ctx.flowId, ctx.flowName, finalMetaFlowId, "PUBLISHED", null);
            log.info("🎉 [FlowPublishWorker] Successfully published Flow '{}' (Meta Flow ID: {})", ctx.flowName, finalMetaFlowId);

        } catch (Exception ex) {
            log.error("❌ [FlowPublishWorker] Failed to publish Flow '{}': {}", ctx.flowName, ex.getMessage(), ex);
            final String errorMsg = ex.getMessage();
            transactionTemplate.executeWithoutResult(status -> {
                FlowPublishJob j = jobRepository.findById(jobId).orElse(null);
                if (j != null) {
                    j.setLastError(errorMsg);
                    if (j.getAttempts() >= j.getMaxAttempts()) {
                        j.setStatus(PublishJobStatus.FAILED);
                        WhatsAppFlow f = flowRepository.findById(ctx.flowId).orElse(null);
                        if (f != null) {
                            f.setStatus(FlowLifecycleStatus.PUBLISH_FAILED);
                            f.setLastSyncError(errorMsg);
                            flowRepository.save(f);
                        }
                    } else {
                        long backoffSeconds = (long) Math.pow(2, j.getAttempts()) * 10;
                        j.setNextRetryAt(LocalDateTime.now().plusSeconds(backoffSeconds));
                        j.setStatus(PublishJobStatus.PENDING);
                    }
                    jobRepository.save(j);
                }
            });

            if (ctx.attempts >= ctx.maxAttempts) {
                auditService.logAction(ctx.flowId, ctx.revisionId, ctx.createdBy,
                        FlowAuditAction.FLOW_PUBLISH_FAILED, "PUBLISHING", "PUBLISH_FAILED", "{\"error\":\"" + errorMsg + "\"}");

                broadcastFlowUpdate(ctx.tenantId, ctx.flowId, ctx.flowName, ctx.revisionMetaFlowId, "PUBLISH_FAILED", errorMsg);
            }
        }
    }

    private void broadcastFlowUpdate(UUID tenantId, UUID flowId, String flowName, String metaFlowId, String status, String error) {
        if (tenantId == null || webSocketPublisher == null) return;
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "FLOW_STATUS_UPDATE");
        payload.put("flowId", flowId != null ? flowId.toString() : "");
        payload.put("metaFlowId", metaFlowId);
        payload.put("name", flowName);
        payload.put("status", status);
        payload.put("error", error);
        payload.put("timestamp", System.currentTimeMillis());
        webSocketPublisher.publishMessage(tenantId, payload);
    }

    private static class PublishJobContext {
        UUID jobId;
        UUID flowId;
        UUID revisionId;
        UUID tenantId;
        String flowName;
        String categoryName;
        String wabaId;
        String activeMetaFlowId;
        String revisionMetaFlowId;
        String fieldsConfigJson;
        String flowJson;
        String accessToken;
        int attempts;
        int maxAttempts;
        com.chatcrmlite.backend.models.User createdBy;
    }
}
